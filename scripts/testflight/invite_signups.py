"""Queue QQ replies, or send TestFlight invitations in a manually started batch."""

import argparse
import hashlib
import json
import os
import re
import subprocess
import tempfile
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path

from beta import DEFAULT_STATE, items, locked, now, read, save


SIGNUP_STATE = Path.home() / 'Library/Application Support/CookBetaSignup'
EMAIL = '接收邀请的邮箱'
CONSENT = '报名信息使用同意'
AGREED = '已阅读报名说明，同意用于本次测试联系与安排'
CREATED = '报名时间'
STATUS = '邀请状态'
QUEUE = 'QQ 群邀请已排队'
WAITING = '待联系'
QQ_SENT = '已发 QQ 群邀请'
INVITED = '已邀请'
OPTED_OUT = '已退出'
INVITATION_STATES = {'INVITED', 'ACCEPTED', 'INSTALLED'}


def address(value):
    email = value.strip().lower() if isinstance(value, str) else ''
    mail_link = re.fullmatch(r'\[([^\[\]]+)\]\(mailto:([^\s()<>]+)\)', email)
    if mail_link:
        label, target = mail_link.groups()
        if label != target:
            return ''
        email = label
    local, _, domain = email.partition('@')
    if len(email) > 254 or len(local) > 64 or '..' in local:
        return ''
    valid_local = (re.fullmatch(r"[a-z0-9!#$%&'*+/=?^_`{|}~.-]+", local)
                   and not local.startswith(('.', '-')) and not local.endswith('.'))
    valid_domain = re.fullmatch(r'(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?', domain)
    return email if valid_local and valid_domain else ''


def call(command, operation, cwd=None):
    try:
        result = subprocess.run(command, cwd=cwd, capture_output=True, text=True,
                                stdin=subprocess.DEVNULL, timeout=45, check=False)
        if result.returncode:
            raise RuntimeError(f'{operation} failed (exit {result.returncode})')
        return json.loads(result.stdout)
    except (OSError, subprocess.TimeoutExpired, ValueError) as error:
        raise RuntimeError(f'{operation} returned no usable response') from error


@dataclass(frozen=True)
class Signup:
    record: str
    email: str
    created: datetime
    status: str
    agreed: bool
    queued: bool

    @property
    def key(self):
        return hashlib.sha256(self.email.encode()).hexdigest()


def pick_signups(rows, cutoff, claims):
    by_email = defaultdict(list)
    eligible = []
    for row in sorted(rows, key=lambda entry: (entry.created, entry.record)):
        by_email[row.email].append(row)
    for same_email in by_email.values():
        if any(row.status in {INVITED, '已参与', OPTED_OUT} for row in same_email):
            continue
        claim = claims.get(same_email[0].key)
        if claim:
            chosen = next((row for row in same_email if row.record == claim['record']), None)
        else:
            delivered = next((row for row in same_email if row.queued or row.status == QQ_SENT), None)
            chosen = delivered or next((row for row in same_email if row.created >= cutoff and row.agreed
                                        and row.status == WAITING), None)
        if (chosen and chosen.created >= cutoff and chosen.agreed
                and chosen.status in {WAITING, QQ_SENT}):
            eligible.append(chosen)
    return sorted(eligible, key=lambda entry: (entry.created, entry.record))


class Feishu:
    def __init__(self, config):
        self.cli = config['larkCommand']
        self.target = ['--base-token', config['baseToken'], '--table-id', config['tableId'], '--as', 'user']
        self.columns = [value for field in config['fieldIds'] for value in ('--field-id', field)]

    def rows(self, record=None):
        rows = []
        offset = 0
        revision = None
        with tempfile.TemporaryDirectory(prefix='cook-beta-signups-') as directory:
            root = Path(directory)
            while True:
                output = f'rows-{offset}.ndjson'
                selection = ['+record-get', '--record-id', record] if record else ['+record-list', '--offset', str(offset)]
                manifest = call([*self.cli, 'base', *selection, *self.target, *self.columns,
                                 '--format', 'ndjson', '--output', f'./{output}'], 'Feishu signup read', root)
                page = (root / output).read_text().splitlines()
                if (manifest.get('records_count') != len(page) or
                        (offset and manifest.get('rev') != revision)):
                    raise RuntimeError('Feishu signup snapshot changed during pagination')
                revision = manifest.get('rev')
                rows.extend(json.loads(line) for line in page)
                if manifest.get('has_more') is False:
                    break
                following = manifest.get('next_offset')
                if record or not isinstance(following, int) or following <= offset or revision is None:
                    raise RuntimeError('Feishu signup snapshot is incomplete')
                offset = following
        signups = []
        for row in rows:
            email = address(row.get(EMAIL))
            if email and row.get(CREATED):
                status = row.get(STATUS) or [WAITING]
                signups.append(Signup(row['record_id'], email, datetime.fromisoformat(row[CREATED]),
                                      status[0], AGREED in (row.get(CONSENT) or []), row.get(QUEUE) is True))
        return signups

    def update(self, row, field, value):
        payload = json.dumps({'update_records': {row.record: {field: value}}}, ensure_ascii=False)
        result = call([*self.cli, 'base', '+record-batch-update', *self.target,
                       '--json', payload, '--format', 'json'], 'Feishu signup update')
        if result.get('ok') is not True or (result.get('data') or {}).get('ignored_fields'):
            raise RuntimeError('Feishu did not accept the signup update')


class TestFlight:
    def __init__(self, config):
        self.command = read(Path(config.get('ascStateDir', DEFAULT_STATE)) / 'config.json')['ascCommand']
        self.app = config['appId']
        candidate = read(Path(__file__).with_name('candidate.json'))
        if candidate.get('appId') != self.app or candidate.get('distributionChannel') != 'external' or not candidate.get('publicLink'):
            raise RuntimeError('The published external TestFlight configuration is invalid')
        self.group = candidate['groupId']
        self.build = candidate['buildId']
        self.public_link = candidate['publicLink']
        self.limit = config['testerLimit']
        if type(self.limit) is not int or self.limit < 1:
            raise RuntimeError('The TestFlight email invitation limit is invalid')

    def run(self, *args):
        return call([*self.command, *args], f'App Store Connect {args[0]} {args[1]}')

    def build_details(self, build_id):
        return self.run('api', 'GET', f'/v1/builds/{build_id}/buildBetaDetail')['data']['attributes']

    def check(self):
        groups = items(self.run('testflight', 'groups', 'list', '--app', self.app, '--paginate'))
        group = next((group['attributes'] for group in groups if group['id'] == self.group), {})
        if group.get('isInternalGroup') is not False or group.get('publicLink') != self.public_link:
            raise RuntimeError('The first TestFlight external group could not be confirmed')
        builds = items(self.run('testflight', 'groups', 'links', 'view', '--group-id', self.group,
                                '--type', 'builds', '--paginate'))
        build = self.run('builds', 'info', '--build-id', self.build)['data']['attributes']
        detail = self.build_details(self.build)
        if (self.build not in {b['id'] for b in builds} or build.get('expired') is not False
                or build.get('buildAudienceType') != 'APP_STORE_ELIGIBLE'
                or build.get('processingState') != 'VALID'
                or detail.get('externalBuildState') != 'IN_BETA_TESTING'
                or not isinstance(detail.get('autoNotifyEnabled'), bool)):
            raise RuntimeError('The assigned TestFlight build is unavailable for external testing')
        self.auto_notify = detail['autoNotifyEnabled']
        for other in builds:
            if self.auto_notify or other['id'] == self.build:
                continue
            detail = self.build_details(other['id'])
            if detail.get('externalBuildState') == 'IN_BETA_TESTING':
                if not isinstance(detail.get('autoNotifyEnabled'), bool):
                    raise RuntimeError('TestFlight group notification settings could not be confirmed')
                self.auto_notify = detail['autoNotifyEnabled']
        return self.limit

    def testers(self, *, group=False, email=None):
        args = ['testflight', 'testers', 'list', '--app', self.app, '--paginate']
        if group:
            args.extend(['--group', self.group, '--invite-type', 'EMAIL'])
        if email:
            args.extend(['--email', email])
        return items(self.run(*args))

    def add(self, row):
        # A live build with automatic notifications sends when the tester is added.
        # If notifications are disabled, ask Apple to send the initial email explicitly.
        operation = 'add' if self.auto_notify else 'invite'
        self.run('testflight', 'testers', operation, '--app', self.app, '--group', self.group, '--email', row.email)


def email_matches(testers, row):
    return [tester['attributes'] for tester in testers
            if address(tester['attributes'].get('email')) == row.email]


def expired_attempt(claim, field):
    return claim.get(field) and datetime.fromisoformat(now()) - datetime.fromisoformat(claim[field]) > timedelta(minutes=20)


def reserved_slots(claims, members):
    registered = {hashlib.sha256(email.encode()).hexdigest() for member in members
                  if (email := address(member['attributes'].get('email')))}
    return sum(1 for key, claim in claims.items()
               if claim.get('appleAt') and not claim.get('appleConfirmed') and key not in registered)


def queue_qq(feishu, rows, claims, persist, apply):
    summary = dict(qq_queued=0, qq_waiting=0, stalled=0)
    for row in rows:
        if row.status != WAITING:
            continue
        claim = claims.get(row.key, {})
        if row.queued or claim.get('queuedAt'):
            summary['qq_waiting'] += 1
            summary['stalled'] += bool(expired_attempt(claim, 'queuedAt'))
        elif not apply or feishu.rows(row.record) == [row]:
            if apply:
                claims[row.key] = {'record': row.record, 'queuedAt': now()}
                persist()
                feishu.update(row, QUEUE, True)
            summary['qq_queued'] += 1
    return summary


def reconcile(feishu, apple, config, state, persist, apply, record_id=None, limit=None):
    claims = state['claims']
    cutoff = datetime.fromisoformat(config['startsAt'])
    rows = pick_signups(feishu.rows(), cutoff, claims)
    if apple is None:
        return queue_qq(feishu, rows, claims, persist, apply)

    summary = dict(testflight_invited=0, new_invites_requested=0, awaiting_capacity=0,
                   previously_registered=0, awaiting_apple=0, stalled=0)
    ready = [row for row in rows if row.status == QQ_SENT and (record_id is None or row.record == record_id)]
    if not ready and apply:
        return summary
    capacity = apple.check()
    if not apply:
        summary['testflight_occupied'] = len(apple.testers(group=True))
        summary['testflight_limit'] = capacity
        summary['testflight_candidates'] = len(ready)
        return summary

    for row in ready:
        current = feishu.rows(row.record)
        if current != [row]:
            continue
        members = apple.testers(group=True)
        matched = email_matches(members, row)
        claim = claims.get(row.key, {})
        if not matched:
            if claim.get('appleAt'):
                summary['awaiting_apple'] += 1
                summary['stalled'] += bool(expired_attempt(claim, 'appleAt'))
                continue
            if email_matches(apple.testers(email=row.email), row):
                summary['previously_registered'] += 1
                continue
            # Recheck occupancy immediately before assigning a place.
            members = apple.testers(group=True)
            if len(members) >= capacity:
                summary['awaiting_capacity'] += 1
                continue
            if len(members) + reserved_slots(claims, members) >= capacity:
                summary['awaiting_apple'] += 1
                continue
            if email_matches(members, row):
                summary['awaiting_apple'] += 1
                continue
            if limit is not None and summary['new_invites_requested'] >= limit:
                continue
            claim = {**claim, 'record': row.record, 'appleAt': now()}
            claims[row.key] = claim
            persist()
            apple.add(row)
            summary['new_invites_requested'] += 1
            matched = email_matches(apple.testers(group=True, email=row.email), row)
        if any(tester.get('state') in INVITATION_STATES for tester in matched):
            if feishu.rows(row.record) == [row]:
                feishu.update(row, STATUS, [INVITED])
                summary['testflight_invited'] += 1
            if claim.get('appleAt') and not claim.get('appleConfirmed'):
                claim['appleConfirmed'] = True
                persist()
        else:
            summary['awaiting_apple'] += 1
            summary['stalled'] += bool(expired_attempt(claim, 'appleAt'))
    return summary


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true', help='Apply the selected operation')
    parser.add_argument('--testflight', action='store_true', help='Use the manual TestFlight mode instead of queueing QQ replies')
    parser.add_argument('--record-id', help='Invite from one Feishu record in manual TestFlight mode')
    parser.add_argument('--limit', type=int, help='Maximum new TestFlight invitations in this manual run')
    options = parser.parse_args()
    if not options.testflight and (options.record_id is not None or options.limit is not None):
        parser.error('--record-id and --limit require --testflight')
    if options.limit is not None and options.limit < 1:
        parser.error('--limit must be positive')
    try:
        with locked(SIGNUP_STATE):
            config = read(SIGNUP_STATE / 'round-two.json')
            ledger = SIGNUP_STATE / 'round-two-ledger.json'
            if not ledger.exists():
                raise RuntimeError('Invitation ledger is missing')
            state = read(ledger)
            apple = TestFlight(config) if options.testflight else None
            summary = reconcile(Feishu(config), apple, config, state,
                                lambda: save(ledger, state), options.apply,
                                record_id=options.record_id, limit=options.limit)
            print(json.dumps(summary))
            return 1 if summary['stalled'] else 0
    except (RuntimeError, OSError, ValueError, KeyError, TypeError) as error:
        # Provider output and exceptions can contain signup data or credentials.
        detail = str(error) if type(error) is RuntimeError else type(error).__name__
        print(json.dumps({'error': detail}))
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
