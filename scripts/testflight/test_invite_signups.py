from dataclasses import replace
from datetime import datetime, timedelta, timezone
from unittest import TestCase
from unittest.mock import Mock

from invite_signups import INVITED, QQ_SENT, QUEUE, STATUS, WAITING, Signup, TestFlight, address, reconcile


START = datetime(2026, 11, 21, tzinfo=timezone.utc)


class SignupTable:
    def __init__(self, *rows):
        self.records = {row.record: row for row in rows}
        self.writes = []

    def rows(self, record=None):
        return [self.records[record]] if record else list(self.records.values())

    def update(self, row, field, value):
        self.writes.append((row.record, field, value))
        change = {'queued': value} if field == QUEUE else {'status': value[0]}
        self.records[row.record] = replace(row, **change)


class ExternalGroup:
    def __init__(self, limit=30):
        self.limit = limit
        self.members = {}
        self.additions = []
        self.state_on_add = 'INVITED'
        self.failure_after_add = False

    def check(self):
        return self.limit

    def testers(self, *, group=False, email=None):
        return [{'attributes': {'email': member, 'state': status}}
                for member, status in self.members.items() if not email or member == email]

    def add(self, row):
        self.additions.append(row.email)
        self.members[row.email] = self.state_on_add
        if self.failure_after_add:
            raise RuntimeError('Connection lost after add')


def signup(record, email, minutes=1, status=WAITING, agreed=True):
    return Signup(record, address(email), START + timedelta(minutes=minutes), status, agreed, False)


class SignupInvitationTests(TestCase):
    def setUp(self):
        self.state = {'claims': {}}
        self.apple = ExternalGroup()

    def run_invites(self, **selection):
        return reconcile(self.feishu, self.apple, {'startsAt': START.isoformat()},
                         self.state, lambda: None, True, **selection)

    def run_qq_replies(self):
        return reconcile(self.feishu, None, {'startsAt': START.isoformat()},
                         self.state, lambda: None, True)

    def test_feishu_email_link_has_to_match_its_displayed_address(self):
        self.assertEqual(address('[Person@Example.Com](mailto:person@example.com)'), 'person@example.com')
        self.assertEqual(address('[person@example.com](mailto:other@example.com)'), '')
        self.assertEqual(address('person@example.com,other@example.com'), '')

    def test_apple_uses_one_email_mechanism_based_on_build_settings(self):
        apple = TestFlight.__new__(TestFlight)
        apple.app, apple.group = 'app', 'group'
        row = signup('first', 'person@example.com')
        for auto_notify, operation in [(True, 'add'), (False, 'invite')]:
            with self.subTest(auto_notify=auto_notify):
                apple.run = Mock()
                apple.auto_notify = auto_notify
                apple.add(row)
                apple.run.assert_called_once_with('testflight', 'testers', operation,
                                                  '--app', 'app', '--group', 'group', '--email', row.email)

    def test_group_occupancy_queries_only_email_invitations(self):
        apple = TestFlight.__new__(TestFlight)
        apple.app, apple.group = 'app', 'group'
        apple.run = Mock(return_value={'data': []})
        self.assertEqual(apple.testers(group=True), [])
        apple.run.assert_called_once_with('testflight', 'testers', 'list', '--app', 'app', '--paginate',
                                         '--group', 'group', '--invite-type', 'EMAIL')

    def test_repeated_signup_queues_and_invites_once(self):
        first = signup('first', 'person@example.com')
        repeat = signup('repeat', ' Person@Example.Com ', minutes=2)
        previous = signup('previous', 'old@example.com', minutes=-1, status=INVITED)
        repeated_previous = signup('repeated_previous', 'old@example.com')
        no_consent = signup('no_consent', 'consent@example.com', agreed=False)
        self.feishu = SignupTable(repeat, first, previous, repeated_previous, no_consent)
        self.run_qq_replies()
        self.run_qq_replies()
        self.assertEqual(self.feishu.writes, [('first', QUEUE, True)])

        self.feishu.records['first'] = replace(self.feishu.records['first'], status=QQ_SENT)
        self.run_qq_replies()
        self.assertEqual(self.apple.additions, [])
        self.run_invites()
        self.run_invites()
        self.assertEqual(self.apple.additions, ['person@example.com'])
        self.assertEqual(self.feishu.writes[-1], ('first', STATUS, [INVITED]))

    def test_last_slot_goes_to_the_earliest_signup(self):
        earlier = signup('early', 'first@example.com', status=QQ_SENT)
        later = signup('late', 'second@example.com', minutes=2, status=QQ_SENT)
        self.feishu = SignupTable(later, earlier)
        self.apple.limit = 1
        summary = self.run_invites()
        self.assertEqual(self.apple.additions, ['first@example.com'])
        self.assertEqual(summary['awaiting_capacity'], 1)
        self.assertEqual(self.feishu.records['late'].status, QQ_SENT)
        self.apple.limit += 1
        self.run_invites()
        self.assertEqual(self.apple.additions, ['first@example.com', 'second@example.com'])

    def test_manual_run_only_invites_selected_qq_recipients(self):
        waiting = signup('waiting', 'waiting@example.com')
        first = signup('first', 'first@example.com', status=QQ_SENT)
        second = signup('second', 'second@example.com', status=QQ_SENT)
        self.feishu = SignupTable(waiting, first, second)
        summary = self.run_invites(record_id='second', limit=1)
        self.assertEqual(summary['new_invites_requested'], 1)
        self.assertEqual(self.apple.additions, [second.email])
        self.assertEqual(self.feishu.writes, [('second', STATUS, [INVITED])])

    def test_uncertain_apple_add_is_reconciled_without_resend(self):
        row = signup('initial', 'ambiguous@example.com', status=QQ_SENT)
        self.feishu = SignupTable(row)
        self.apple.failure_after_add = True
        with self.assertRaises(RuntimeError):
            self.run_invites()
        self.assertEqual(self.feishu.writes, [])
        self.run_invites()
        self.assertEqual(self.apple.additions, [row.email])
        self.assertEqual(self.feishu.records[row.record].status, INVITED)

    def test_apple_pending_state_does_not_update_feishu_or_retry(self):
        row = signup('initial', 'pending@example.com', status=QQ_SENT)
        self.feishu = SignupTable(row)
        self.apple.state_on_add = 'NOT_INVITED'
        self.run_invites()
        self.run_invites()
        self.assertEqual(self.apple.additions, [row.email])
        self.assertEqual(self.feishu.writes, [])
        self.apple.members[row.email] = 'INVITED'
        self.run_invites()
        self.assertEqual(self.feishu.records[row.record].status, INVITED)
