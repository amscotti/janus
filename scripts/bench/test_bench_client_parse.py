#!/usr/bin/env python3
"""Unit tests for bench_client parsers (no network, no tools)."""
from __future__ import annotations

import unittest

from bench_client import _first, parse_wrk_latencies


# Sample shape from wrk 4.x Latency Distribution (percentages have three decimals).
WRK_SAMPLE = """\
Running 10s test @ http://127.0.0.1:18090/v1/chat/completions
  2 threads and 10 connections
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency     2.15ms    0.40ms   5.00ms   85.00%
    Req/Sec     2.10k   100.00     2.20k    90.00%
  Latency Distribution
     50.000%    2.00ms
     75.000%    2.50ms
     90.000%    3.00ms
     99.000%    4.00ms
  42000 requests in 10.00s, 5.00MB read
Requests/sec:   4200.00
Transfer/sec:    512.00KB
"""

# The old buggy pattern matched bare "50%" and never hit wrk's "50.000%".
OLD_BUGGY = r"50%\s+([\d.]+)ms"


class WrkParseTest(unittest.TestCase):
    def test_p50_and_high_from_real_shape(self) -> None:
        # wrk has no 95th; high percentile falls back to 90% → 3.00ms
        p50, high, pct = parse_wrk_latencies(WRK_SAMPLE)
        self.assertEqual(p50, 2.0)
        self.assertEqual(high, 3.0)
        self.assertEqual(pct, 90)

    def test_explicit_95_preferred_when_present(self) -> None:
        text = WRK_SAMPLE + "\n     95.000%    3.50ms\n"
        p50, high, pct = parse_wrk_latencies(text)
        self.assertEqual(p50, 2.0)
        self.assertEqual(high, 3.5)
        self.assertEqual(pct, 95)

    def test_integer_percent_form(self) -> None:
        text = "Latency Distribution\n     50%   1.5ms\n     90%   4.0ms\n"
        p50, high, pct = parse_wrk_latencies(text)
        self.assertEqual(p50, 1.5)
        self.assertEqual(high, 4.0)
        self.assertEqual(pct, 90)

    def test_rps_parses(self) -> None:
        rps = _first(r"Requests/sec:\s*([\d.]+)", WRK_SAMPLE)
        self.assertEqual(float(rps), 4200.0)

    def test_old_buggy_pattern_does_not_match_decimal_percent(self) -> None:
        # Bare "50%" never matches wrk 4.x "50.000%" — the bug this fixes.
        self.assertIsNone(_first(OLD_BUGGY, WRK_SAMPLE))

    def test_missing_distribution_raises(self) -> None:
        with self.assertRaises(ValueError):
            parse_wrk_latencies("Requests/sec: 1.0\n")


if __name__ == "__main__":
    unittest.main()
