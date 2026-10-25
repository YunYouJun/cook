#!/usr/bin/env python3
"""Local TestFlight release gate and private AI work queue. Python 3.11+, ASC CLI.

No shell execution, embedded credentials, public feedback export or message sends.
The desktop heartbeat supplies a product-scoped Support snapshot separately.
"""
import argparse
import contextlib
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from datetime import datetime, timezone

CHECKS = ('upgrade', 'file_roundtrip', 'production_sync', 'account_isolation',
          'feedback_privacy', 'site_privacy')
SIMULATOR_CHECKS = ('upgrade', 'file_roundtrip', 'local_persistence', 'local_isolation',
                    'feedback_privacy', 'site_privacy', 'notifications', 'account_session')
ACCEPTANCE_POLICIES = {'production': CHECKS, 'simulator': SIMULATOR_CHECKS}
APPROVED_EXTERNAL_STATES = ('BETA_APPROVED', 'READY_FOR_BETA_TESTING', 'IN_BETA_TESTING')
STATES = ('new', 'triaged', 'in_progress', 'waiting_user', 'verified', 'reply_draft', 'closed')
DEFAULT_STATE = Path.home() / 'Library/Application Support/CookExternalBeta'


def now():
    return datetime.now(timezone.utc).isoformat()


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def read(path):
    return json.loads(Path(path).read_text())


def save(path, value):
    """Atomic owner-only writes; no partly written queue or gate evidence."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd, temporary = tempfile.mkstemp(prefix='.pending-', dir=path.parent)
    try:
        with os.fdopen(fd, 'w') as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.write('\n')
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)


def items(response):
    data = response.get('data')
    if not isinstance(data, list) or response.get('links', {}).get('next'):
        raise ValueError('Incomplete ASC collection; no state advanced')
    total = response.get('meta', {}).get('paging', {}).get('total')
    if total is not None and total != len(data):
        raise ValueError('ASC collection count mismatch')
    return data


def acceptance_errors(candidate, evidence, current=None):
    current = current or datetime.now(timezone.utc)
    errors = []
    for key in ('buildId', 'sourceCommit', 'ipaSha256'):
        if evidence.get(key) != candidate[key]:
            errors.append(f'acceptance.{key}')
    if not evidence.get('approvedBy'):
        errors.append('acceptance.approvedBy')
    # Scope is frozen with the candidate. A simulator report cannot satisfy the
    # default production policy or claim real CloudKit/account-switch coverage.
    policy = candidate.get('acceptancePolicy', 'production')
    if policy not in ACCEPTANCE_POLICIES:
        errors.append('acceptance.policy')
        return errors
    if evidence.get('acceptancePolicy', 'production') != policy:
        errors.append('acceptance.policy')
    for key in ACCEPTANCE_POLICIES[policy]:
        check = evidence.get('checks', {}).get(key, {})
        valid = check.get('passed') is True
        try:
            tested = datetime.fromisoformat(check['testedAt'].replace('Z', '+00:00'))
            path = Path(check['evidencePath'])
            valid &= tested <= current and path.is_absolute() and path.is_file()
            valid &= bool(check.get('evidenceSha256')) and digest(path) == check['evidenceSha256']
        except (KeyError, OSError, ValueError, TypeError, AttributeError):
            valid = False
        if key == 'production_sync':
            valid &= len(set(check.get('physicalDeviceLabels', []))) >= 2
        if not valid:
            errors.append(f'acceptance.{key}')
    return errors


def gate(candidate, snapshot, evidence):
    errors = acceptance_errors(candidate, evidence)
    build = snapshot['build']
    attr = build['attributes']
    group = snapshot['group']['attributes']
    if build['id'] != candidate['buildId'] or attr.get('version') != candidate['buildNumber']:
        errors.append('build.identity')
    if attr.get('processingState') != 'VALID' or attr.get('buildAudienceType') != 'APP_STORE_ELIGIBLE':
        errors.append('build.eligibility')
    if attr.get('expired') is not False:
        errors.append('build.expired')
    try:
        if datetime.fromisoformat(attr['expirationDate']) <= datetime.now(timezone.utc):
            errors.append('build.expiration')
    except (KeyError, ValueError, TypeError):
        errors.append('build.expiration')
    if attr.get('usesNonExemptEncryption') is not False:
        errors.append('build.compliance')
    if snapshot['externalState'] not in APPROVED_EXTERNAL_STATES:
        errors.append('apple.approval')
    if snapshot['group']['id'] != candidate['groupId'] or group.get('isInternalGroup') is not False:
        errors.append('group.identity')
    if group.get('hasAccessToAllBuilds') is True or snapshot['buildIds'] != [candidate['buildId']]:
        errors.append('group.unverified_builds')
    if candidate.get('testerLimit') != 30 or snapshot['testers'] > 30:
        errors.append('group.capacity')
    return errors


class Controller:
    def __init__(self, candidate, directory, config):
        self.c = candidate
        self.directory = Path(directory)
        self.config = config

    def asc(self, *args):
        runner = self.config.get('ascCommand', ['asc'])
        if not isinstance(runner, list) or not all(isinstance(v, str) for v in runner):
            raise ValueError('ascCommand must be an argv array')
        result = subprocess.run([*runner, *args], capture_output=True, text=True, timeout=55,
                                stdin=subprocess.DEVNULL, check=False)
        if result.returncode:
            # CLI stderr can include user content, credentials or PII.
            raise RuntimeError(f'ASC {args[0]} {args[1]} failed (exit {result.returncode})')
        return json.loads(result.stdout)

    def snapshot(self):
        c = self.c
        build = self.asc('builds', 'info', '--app', c['appId'], '--version', c['version'],
                         '--build-number', c['buildNumber'], '--platform', 'IOS')['data']
        groups = items(self.asc('testflight', 'groups', 'list', '--app', c['appId'], '--paginate'))
        group = next(g for g in groups if g['id'] == c['groupId'])
        builds = items(self.asc('testflight', 'groups', 'links', 'view', '--group-id',
                               c['groupId'], '--type', 'builds', '--paginate'))
        testers = items(self.asc('testflight', 'testers', 'list', '--app', c['appId'],
                                '--group', c['groupId'], '--paginate'))
        details = items(self.asc('testflight', 'distribution', 'view', '--build-id', c['buildId']))
        detail = next(d for d in details if d['id'] == c['buildId'])
        return {'checkedAt': now(), 'build': build, 'group': group,
                'buildIds': sorted(b['id'] for b in builds), 'testers': len(testers),
                'externalState': detail['attributes'].get('externalBuildState')}

    def set_link(self, enabled):
        args = ['testflight', 'groups', 'edit', '--id', self.c['groupId'],
                f'--public-link-enabled={str(enabled).lower()}']
        if enabled:
            # Apple rejects limit changes while a public link is disabled.
            args += ['--public-link-limit-enabled=true', '--public-link-limit', '30']
        return self.asc(*args)

    def acceptance(self):
        path = self.directory / 'acceptance.json'
        return read(path) if path.exists() else {}

    def status(self):
        snapshot = self.snapshot()
        result = {'checkedAt': now(), 'buildId': self.c['buildId'],
                  'acceptancePolicy': self.c.get('acceptancePolicy', 'production'),
                  'blockers': gate(self.c, snapshot, self.acceptance()),
                  'externalState': snapshot['externalState'], 'testers': snapshot['testers'],
                  'publicLinkEnabled': snapshot['group']['attributes'].get('publicLinkEnabled'),
                  'configuredLimit': snapshot['group']['attributes'].get('publicLinkLimit'),
                  'desiredLimit': 30}
        save(self.directory / 'status.json', result)
        return result

    def reconcile(self, apply=False):
        try:
            snapshot = self.snapshot()
            errors = gate(self.c, snapshot, self.acceptance())
        except Exception:
            if apply:
                save(self.directory / 'invitation.json', {'open': False, 'checkedAt': now()})
                self.set_link(False)
            raise
        desired = not errors
        attr = snapshot['group']['attributes']
        change = attr.get('publicLinkEnabled') is not desired
        change |= desired and (attr.get('publicLinkLimit') != 30 or attr.get('publicLinkLimitEnabled') is not True)
        if apply:
            try:
                if change:
                    self.set_link(desired)
                verified = self.snapshot()
                after = verified['group']['attributes']
                if after.get('publicLinkEnabled') is not desired:
                    raise ValueError('Public link readback mismatch')
                if desired:
                    if gate(self.c, verified, self.acceptance()):
                        raise ValueError('Gate changed during opening')
                    if after.get('publicLinkLimit') != 30 or after.get('publicLinkLimitEnabled') is not True:
                        raise ValueError('Tester limit readback mismatch')
                    if not re.fullmatch(r'https://testflight\.apple\.com/join/[A-Za-z0-9]+', after.get('publicLink') or ''):
                        raise ValueError('Missing verified public link')
                save(self.directory / 'invitation.json', {'open': desired, 'checkedAt': now(),
                     'buildId': self.c['buildId'], 'url': after.get('publicLink') if desired else None})
            except Exception:
                save(self.directory / 'invitation.json', {'open': False, 'checkedAt': now()})
                self.set_link(False)
                raise
        return {'open': desired if apply else None, 'wouldOpen': desired,
                'blockers': errors, 'changed': change and apply}

    def submit(self, apply=False):
        s = self.snapshot()
        # Apple review may precede device acceptance, but never opens invitations.
        errors = [e for e in gate(self.c, s, self.acceptance())
                  if not e.startswith('acceptance.') and e != 'apple.approval']
        if s['group']['attributes'].get('publicLinkEnabled') is not False:
            errors.append('group.must_be_closed')
        if errors:
            raise ValueError('Submission blocked: ' + ', '.join(errors))
        if s['externalState'] != 'READY_FOR_BETA_SUBMISSION':
            return {'submitted': False, 'externalState': s['externalState'], 'reason': 'No duplicate submission'}
        ipa = self.config.get('ipaPath')
        if not ipa or digest(ipa) != self.c['ipaSha256']:
            raise ValueError('Exact archived IPA is missing or hash changed')
        if apply:
            submissions = items(self.asc('testflight', 'review', 'submissions', 'list', '--build-id', self.c['buildId'], '--paginate'))
            if submissions:
                return {'submitted': False, 'reason': 'Existing submission; inspect its result before resubmitting'}
            intent = self.directory / 'submission-intent.json'
            if intent.exists() and read(intent).get('buildId') == self.c['buildId']:
                raise RuntimeError('Prior submission outcome unknown; reconcile with Apple before retrying')
            save(intent, {'buildId': self.c['buildId'], 'startedAt': now()})
            receipt = self.asc('testflight', 'review', 'submit', '--build-id', self.c['buildId'], '--confirm')
            save(self.directory / 'review-submission.json', receipt)
        return {'submitted': apply, 'buildId': self.c['buildId'], 'invitations': 'closed'}

    def feedback(self):
        # Entire app history, not only the newest build; all pages or fail.
        sources = {}
        for kind in ('feedback', 'crashes'):
            sources[f'testflight-{kind}'] = items(self.asc('testflight', kind, 'list',
                                                         '--app', self.c['appId'], '--paginate', '--include', 'build'))
        return merge_feedback(self.directory, sources)


def merge_feedback(directory, sources):
    directory = Path(directory)
    path = directory / 'work-items.json'
    state = read(path) if path.exists() else {'schema': 1, 'items': {}, 'sources': {}}
    changed = []
    for source, records in sources.items():
        if source not in ('testflight-feedback', 'testflight-crashes', 'support') or not isinstance(records, list):
            raise ValueError('Unknown feedback source')
        if len({r.get('id') for r in records}) != len(records):
            raise ValueError('Duplicate source IDs; fetch a complete stable snapshot again')
        for record in records:
            if source == 'support' and record.get('productId') != 'cook':
                raise ValueError('Support export contains another product')
            identifier = record.get('id')
            if not isinstance(identifier, str) or not re.fullmatch(r'[\w-]{1,128}', identifier):
                raise ValueError('Invalid source ID')
            key = f'{source}:{identifier}'
            fingerprint = hashlib.sha256(json.dumps(record, sort_keys=True).encode()).hexdigest()
            existing = state['items'].get(key, {})
            if existing.get('sourceHash') != fingerprint:
                changed.append(key)
            state['items'][key] = {**existing, 'id': key, 'source': source,
                                  'sourceHash': fingerprint, 'sourceRecord': record,
                                  'firstSeenAt': existing.get('firstSeenAt', now()),
                                  'lastSeenAt': now(), 'status': existing.get('status', 'new'),
                                  'investigation': existing.get('investigation', {}),
                                  'reply': existing.get('reply', {'status': 'not_drafted'})}
        state['sources'][source] = {'lastSuccessAt': now(), 'count': len(records)}
    save(path, state)
    counts = {status: sum(v['status'] == status for v in state['items'].values()) for status in STATES}
    report = {'generatedAt': now(), 'changedIds': changed, 'counts': counts,
              'sources': state['sources'], 'total': len(state['items'])}
    save(directory / 'feedback-summary.json', report)
    return report


def update_work(directory, identifier, patch):
    if set(patch) - {'status', 'investigation', 'reply'}:
        raise ValueError('Only workflow fields may be changed')
    path = Path(directory) / 'work-items.json'
    state = read(path)
    item = {**state['items'][identifier], **patch}
    if item['status'] not in STATES:
        raise ValueError('Unknown work status')
    investigation = item['investigation']
    if item['status'] in ('verified', 'reply_draft', 'closed'):
        if not all(investigation.get(k) for k in ('resolution', 'verificationEvidence', 'releasedBuild')):
            raise ValueError('Resolution, verification evidence and released build are required')
    reply = item['reply']
    if item['status'] == 'reply_draft' and (reply.get('status') != 'draft' or not reply.get('body')):
        raise ValueError('Reply draft is required')
    if item['status'] == 'closed':
        if reply.get('status') not in ('delivered', 'unreachable'):
            raise ValueError('Closure requires delivery receipt or unreachable reason')
        if not reply.get('deliveryReceipt') and not (reply.get('status') == 'unreachable' and reply.get('reason')):
            raise ValueError('Missing delivery receipt or unreachable reason')
    item['updatedAt'] = now()
    state['items'][identifier] = item
    save(path, state)
    return {'id': identifier, 'status': item['status']}


@contextlib.contextmanager
def locked(directory):
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(directory, 0o700)
    with (directory / '.lock').open('a') as stream:
        fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('status', 'submit-review', 'reconcile', 'feedback', 'import-support', 'work-update'))
    parser.add_argument('--state-dir', type=Path, default=DEFAULT_STATE)
    parser.add_argument('--candidate', type=Path, default=Path(__file__).with_name('candidate.json'))
    parser.add_argument('--apply', action='store_true', help='Explicitly mutate Apple state; never used by submit in the heartbeat')
    parser.add_argument('--input', type=Path, help='Private JSON Support snapshot or work patch')
    parser.add_argument('--id', help='Work item source:id')
    args = parser.parse_args()
    with locked(args.state_dir):
        candidate = read(args.candidate)
        config_path = args.state_dir / 'config.json'
        config = read(config_path) if config_path.exists() else {}
        controller = Controller(candidate, args.state_dir, config)
        if args.command == 'status':
            result = controller.status()
        elif args.command == 'reconcile':
            result = controller.reconcile(args.apply)
        elif args.command == 'submit-review':
            result = controller.submit(args.apply)
        elif args.command == 'feedback':
            result = controller.feedback()
        elif args.command == 'import-support':
            payload = read(args.input)
            timestamp = datetime.fromisoformat(payload['fetchedAt'].replace('Z', '+00:00'))
            age = (datetime.now(timezone.utc) - timestamp).total_seconds()
            if payload.get('envId') != 'yunlefun-8g7ybcxc7345c490' or payload.get('complete') is not True or not 0 <= age < 3600:
                raise ValueError('Incomplete, stale or wrong-environment Support export')
            result = merge_feedback(args.state_dir, {'support': payload['items']})
        else:
            result = update_work(args.state_dir, args.id, read(args.input))
        print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, TypeError, AttributeError, OSError, RuntimeError, StopIteration, subprocess.TimeoutExpired) as error:
        # Do not echo arbitrary source text, process args, paths or HTTP bodies.
        print(json.dumps({'error': type(error).__name__, 'message': 'Operation incomplete; invitations must remain closed. Check private configuration and source availability.'}), file=sys.stderr)
        sys.exit(1)
