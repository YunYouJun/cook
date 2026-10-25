"""Regression tests for gates that would otherwise expose unaccepted builds."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import beta


class BetaTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='cook-beta-test-')
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.c = beta.read(Path(__file__).with_name('candidate.json'))
        # Keep default production-policy tests independent of the live candidate policy.
        self.c.pop('acceptancePolicy', None)
        evidence = self.directory / 'device-evidence.txt'
        evidence.write_text('Synthetic device evidence for isolated gate tests, never production.')
        check = {'passed': True, 'testedAt': '2026-10-01T00:00:00Z',
                 'evidencePath': str(evidence), 'evidenceSha256': beta.digest(evidence),
                 'physicalDeviceLabels': ['synthetic-phone', 'synthetic-tablet']}
        self.e = {**{k: self.c[k] for k in ('buildId', 'sourceCommit', 'ipaSha256')},
                  'approvedBy': 'test-fixture', 'checks': {k: dict(check) for k in beta.CHECKS}}
        self.s = {'build': {'id': self.c['buildId'], 'attributes': {
            'version': self.c['buildNumber'], 'processingState': 'VALID', 'buildAudienceType': 'APP_STORE_ELIGIBLE',
            'expired': False, 'usesNonExemptEncryption': False, 'expirationDate': '2099-01-01T00:00:00Z'}},
            'group': {'id': self.c['groupId'], 'attributes': {
                'isInternalGroup': False, 'publicLinkEnabled': False}},
            'buildIds': [self.c['buildId']], 'externalState': 'READY_FOR_BETA_TESTING', 'testers': 0}
        beta.save(self.directory / 'acceptance.json', self.e)

    def controller(self):
        return beta.Controller(self.c, self.directory, {})

    def test_all_gate_inputs_required(self):
        self.assertEqual(beta.gate(self.c, self.s, self.e), [])
        cases = [({}, 'acceptance.buildId')]
        for key in beta.CHECKS:
            value = copy.deepcopy(self.e)
            value['checks'][key]['passed'] = False
            cases.append((value, f'acceptance.{key}'))
        for evidence, expected in cases:
            self.assertIn(expected, beta.gate(self.c, self.s, evidence))
        self.e['checks']['upgrade']['testedAt'] = None
        self.assertIn('acceptance.upgrade', beta.gate(self.c, self.s, self.e))

    def test_new_candidate_and_one_device_invalidate_acceptance(self):
        self.e['buildId'] = 'old-build'
        self.e['checks']['production_sync']['physicalDeviceLabels'] = ['one-phone', 'one-phone']
        errors = beta.gate(self.c, self.s, self.e)
        self.assertIn('acceptance.buildId', errors)
        self.assertIn('acceptance.production_sync', errors)

    def test_evidence_tampering(self):
        Path(self.e['checks']['upgrade']['evidencePath']).write_text('changed evidence')
        self.assertIn('acceptance.upgrade', beta.gate(self.c, self.s, self.e))

    def test_review_pending_expired_extra_build_and_capacity(self):
        self.s['externalState'] = 'WAITING_FOR_BETA_REVIEW'
        self.s['build']['attributes']['expired'] = True
        self.s['buildIds'].append('unverified-build')
        self.s['testers'] = 31
        self.assertTrue({'apple.approval', 'build.expired', 'group.unverified_builds', 'group.capacity'}
                        <= set(beta.gate(self.c, self.s, self.e)))

    def test_approved_build_does_not_require_another_review(self):
        for state in beta.APPROVED_EXTERNAL_STATES:
            with self.subTest(state=state):
                self.s['externalState'] = state
                self.assertEqual(beta.gate(self.c, self.s, self.e), [])
                c = self.controller()
                with patch.object(c, 'snapshot', return_value=self.s), patch.object(c, 'asc') as mutate:
                    self.assertFalse(c.submit(True)['submitted'])
                    mutate.assert_not_called()
        for state in ('READY_FOR_BETA_SUBMISSION', 'BETA_REJECTED', 'IN_BETA_REVIEW', 'UNKNOWN'):
            self.s['externalState'] = state
            self.assertIn('apple.approval', beta.gate(self.c, self.s, self.e))

    def test_simulator_policy_requires_its_own_complete_evidence(self):
        self.c['acceptancePolicy'] = 'simulator'
        self.assertIn('acceptance.policy', beta.gate(self.c, self.s, self.e))
        check = self.e['checks']['upgrade']
        self.e['acceptancePolicy'] = 'simulator'
        self.e['checks'] = {key: dict(check) for key in beta.SIMULATOR_CHECKS}
        for value in self.e['checks'].values():
            value.pop('physicalDeviceLabels')
        self.assertEqual(beta.gate(self.c, self.s, self.e), [])
        for key in beta.SIMULATOR_CHECKS:
            missing = copy.deepcopy(self.e)
            del missing['checks'][key]
            self.assertIn(f'acceptance.{key}', beta.gate(self.c, self.s, missing))
        # Neither an unknown policy nor a simulator record can downgrade a
        # production candidate's requirement for two real devices.
        self.c['acceptancePolicy'] = 'production'
        errors = beta.gate(self.c, self.s, self.e)
        self.assertIn('acceptance.policy', errors)
        self.assertIn('acceptance.production_sync', errors)
        self.c['acceptancePolicy'] = 'unknown'
        self.assertIn('acceptance.policy', beta.gate(self.c, self.s, self.e))

    def test_open_sets_limit_in_same_request(self):
        c = self.controller()
        calls = []
        c.asc = lambda *args: calls.append(args)
        c.set_link(True)
        self.assertEqual(len(calls), 1)
        self.assertIn('--public-link-enabled=true', calls[0])
        self.assertIn('--public-link-limit-enabled=true', calls[0])
        self.assertEqual(calls[0][-2:], ('--public-link-limit', '30'))

    def test_dry_run_never_mutates(self):
        c = self.controller()
        with patch.object(c, 'snapshot', return_value=self.s), patch.object(c, 'set_link') as mutate:
            self.assertTrue(c.reconcile()['wouldOpen'])
            mutate.assert_not_called()

    def test_missing_evidence_closes_existing_link(self):
        c = self.controller()
        self.s['group']['attributes']['publicLinkEnabled'] = True
        after = copy.deepcopy(self.s)
        after['group']['attributes']['publicLinkEnabled'] = False
        with patch.object(c, 'acceptance', return_value={}), patch.object(c, 'snapshot', side_effect=[self.s, after]), patch.object(c, 'set_link') as mutate:
            self.assertFalse(c.reconcile(True)['open'])
            mutate.assert_called_once_with(False)

    def test_post_open_network_failure_rolls_back(self):
        c = self.controller()
        with patch.object(c, 'snapshot', side_effect=[self.s, RuntimeError('offline')]), patch.object(c, 'set_link') as mutate:
            with self.assertRaises(RuntimeError):
                c.reconcile(True)
            self.assertEqual([v.args for v in mutate.call_args_list], [(True,), (False,)])
        self.assertFalse(beta.read(self.directory / 'invitation.json')['open'])

    def test_limit_readback_failure_rolls_back(self):
        c = self.controller()
        wrong = copy.deepcopy(self.s)
        wrong['group']['attributes'].update(publicLinkEnabled=True, publicLinkLimit=100)
        with patch.object(c, 'snapshot', side_effect=[self.s, wrong]), patch.object(c, 'set_link') as mutate:
            with self.assertRaises(ValueError):
                c.reconcile(True)
            self.assertEqual(mutate.call_args.args, (False,))

    def test_partial_feedback_response_is_not_success(self):
        for response in ({'data': [], 'links': {'next': 'next-page'}},
                         {'data': [], 'meta': {'paging': {'total': 2}}}):
            with self.assertRaises(ValueError):
                beta.items(response)

    def test_feedback_is_idempotent_private_and_preserves_ai_work(self):
        records = [{'id': 'fixture', 'productId': 'cook', 'description': 'private fixture'}]
        first = beta.merge_feedback(self.directory, {'support': records})
        self.assertEqual(first['total'], 1)
        beta.update_work(self.directory, 'support:fixture', {'status': 'triaged', 'investigation': {'notes': 'retained'}})
        second = beta.merge_feedback(self.directory, {'support': records})
        self.assertEqual(second['changedIds'], [])
        self.assertEqual(second['counts']['triaged'], 1)
        path = self.directory / 'work-items.json'
        self.assertEqual(path.stat().st_mode & 0o777, 0o600)
        self.assertNotIn('private fixture', json.dumps(second))
        self.assertEqual(beta.read(path)['items']['support:fixture']['investigation']['notes'], 'retained')

    def test_other_product_rejected_without_overwriting_queue(self):
        with self.assertRaises(ValueError):
            beta.merge_feedback(self.directory, {'support': [{'id': 'x', 'productId': 'drive'}]})
        self.assertFalse((self.directory / 'work-items.json').exists())

    def test_closure_needs_verification_and_reply_receipt(self):
        beta.merge_feedback(self.directory, {'support': [{'id': 'x', 'productId': 'cook'}]})
        with self.assertRaises(ValueError):
            beta.update_work(self.directory, 'support:x', {'status': 'closed'})
        verification = {'resolution': 'fixed', 'verificationEvidence': 'private test report', 'releasedBuild': '2.0.0 (6)'}
        with self.assertRaises(ValueError):
            beta.update_work(self.directory, 'support:x', {'status': 'closed', 'investigation': verification, 'reply': {'status': 'draft'}})
        result = beta.update_work(self.directory, 'support:x', {'status': 'closed', 'investigation': verification,
                                  'reply': {'status': 'unreachable', 'reason': 'Anonymous tester; no contact'}})
        self.assertEqual(result['status'], 'closed')


if __name__ == '__main__':
    unittest.main()
