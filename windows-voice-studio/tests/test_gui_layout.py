from __future__ import annotations

import unittest

from mkvoice_studio.gui import initial_window_size


class GuiLayoutPolicyTest(unittest.TestCase):
    def test_initial_size_leaves_room_for_windows_chrome_on_768p(self) -> None:
        self.assertEqual((1040, 668), initial_window_size(1366, 768))

    def test_initial_size_is_bounded_for_small_and_large_displays(self) -> None:
        self.assertEqual((720, 500), initial_window_size(800, 600))
        self.assertEqual((1040, 760), initial_window_size(1920, 1080))


if __name__ == "__main__":
    unittest.main()
