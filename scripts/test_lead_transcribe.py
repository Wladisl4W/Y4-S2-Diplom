"""Small deterministic checks for the lead tracker; run with python -m unittest discover -s scripts."""

import importlib.util
from pathlib import Path
import unittest


source = Path(__file__).resolve().parents[1] / "src/main/resources/lead_transcribe.py"
spec = importlib.util.spec_from_file_location("lead_transcribe", source)
lead = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lead)


class LeadTrackerTest(unittest.TestCase):
    def test_keeps_lower_lead_and_reports_harmony(self):
        notes, alternatives = lead.select_lead([
            (0, 0.5, 60, 0.95), (0, 0.5, 64, 0.60),
            (0.5, 1, 62, 0.90), (0.5, 1, 65, 0.55),
        ], 1)
        self.assertEqual([60, 62], [note[2] for note in notes])
        self.assertEqual([64, 65], [note[2] for note in alternatives])

    def test_silence_breaks_notes(self):
        notes, _ = lead.select_lead([(0, 0.3, 60, 0.9), (0.7, 1, 60, 0.9)], 1)
        self.assertEqual(2, len(notes))
        self.assertGreater(notes[1][0] - notes[0][0], 0.5)


if __name__ == "__main__":
    unittest.main()
