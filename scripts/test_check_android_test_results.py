"""Regression tests for the CI result gate; no emulator or dependencies needed."""

from pathlib import Path
import tempfile
import unittest

from check_android_test_results import COUCH_SUITE, check_reports


class CheckAndroidTestResultsTest(unittest.TestCase):
    def check(self, xml):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "debug"
            path.mkdir()
            if xml is not None:
                (path / "TEST-test(AVD) - 13.xml").write_text(xml)
            return check_reports(Path(directory))

    def suite(self, body='<testcase name="sync"/>', attributes=""):
        return f'<testsuite name="{COUCH_SUITE}" {attributes}>{body}</testsuite>'

    def test_nested_agp_format_passes(self):
        self.assertEqual([], self.check(f'<testsuites tests="1" failures="0">{self.suite()}</testsuites>'))

    def test_single_suite_passes(self):
        self.assertEqual([], self.check(self.suite()))

    def test_missing_empty_malformed_and_wrong_reports_fail(self):
        for xml in (None, "", "<testsuites>", "<html/>", "<testsuites/>", self.suite("")):
            with self.subTest(xml=xml):
                self.assertTrue(self.check(xml))

    def test_failure_and_error_elements_fail_even_with_zero_counters(self):
        for tag in ("failure", "error"):
            with self.subTest(tag=tag):
                self.assertTrue(self.check(self.suite(f'<testcase name="sync"><{tag}/></testcase>')))

    def test_aggregate_failures_and_errors_fail(self):
        for counter in ("failures", "errors"):
            for value in ("1", "-1", "invalid"):
                self.assertTrue(self.check(f'<testsuites {counter}="{value}">{self.suite()}</testsuites>'))

    def test_couch_skips_fail_even_with_other_passing_cases(self):
        self.assertTrue(self.check(self.suite('<testcase name="pass"/><testcase name="skip"><skipped/></testcase>')))
        for status in ("notrun", "skipped", "disabled"):
            self.assertTrue(self.check(self.suite(f'<testcase name="sync" status="{status}"/>')))
        for counter in ("skipped", "disabled"):
            self.assertTrue(self.check(self.suite(attributes=f'{counter}="1"')))

    def test_non_couch_failure_fails(self):
        self.assertTrue(self.check(f'<testsuites>{self.suite()}<testsuite name="other"><testcase><failure/></testcase></testsuite></testsuites>'))

    def test_mixed_class_suite_supported(self):
        self.assertEqual([], self.check(f'<testsuite name="device"><testcase classname="{COUCH_SUITE}" name="sync"/></testsuite>'))

    def test_other_class_does_not_satisfy_required_suite(self):
        self.assertTrue(self.check('<testsuite name="other"><testcase name="sync"/></testsuite>'))


if __name__ == "__main__":
    unittest.main()
