import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("profile_resources", Path(__file__).parents[1] / "profile-resources.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
RunWatchdog = module.RunWatchdog


class WatchdogTest(unittest.TestCase):
    def test_progress_cannot_extend_two_hour_limit(self):
        watchdog = RunWatchdog(100)
        for cycle in range(1, 121):
            now = 100 + cycle * 59
            watchdog.observe(cycle, "cycle_done", now)
            watchdog.check(now)
        watchdog.observe(121, "still_active", 7300)
        with self.assertRaisesRegex(RuntimeError, "7200 seconds"):
            watchdog.check(7300)

    def test_stalled_step_is_named_and_repeated_reads_do_not_reset_timeout(self):
        watchdog = RunWatchdog(0)
        watchdog.observe(20, "rotation_landscape_idle", 60)
        for now in range(70, 240, 10):
            watchdog.observe(20, "rotation_landscape_idle", now)
            watchdog.check(now)
        with self.assertRaisesRegex(RuntimeError, "rotation_landscape_idle"):
            watchdog.check(240)

    def test_normal_minute_pause_between_cycles_is_allowed(self):
        watchdog = RunWatchdog(0)
        for cycle in range(1, 120):
            now = cycle * 59
            watchdog.observe(cycle, "cycle_done", now)
            watchdog.check(now + 58)


if __name__ == "__main__":
    unittest.main()
