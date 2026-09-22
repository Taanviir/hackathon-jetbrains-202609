# Paired held-out recall uncertainty

Across the 30 held-out Koog tasks, fixed Laya+BM25 minus full-source BM25 recall@10 averaged **-0.162**. The paired task bootstrap's 95% percentile interval was **[-0.269, -0.070]**. Laya improved 0 tasks, tied 20, and worsened 10.

The calculation resampled whole paired tasks with replacement 10,000 times using Python's `random.Random(20260923)`. Each task's recall was recomputed from the saved exact rankings and modified-file truth, before four-decimal rounding; the interval uses the 2.5th and 97.5th empirical percentiles with linear interpolation. This describes uncertainty across this task sample; the tasks share one repository and time period, so it does not establish broader generalization.

Source: `laya-benchmark-extended.json`, SHA-256 `2b944e126f8d49bdce9c3bb5a8b6f4a23cbfd064f2c735a7942ca7c464022e2b`. The compact JSON lists each task's exact hit counts and paired difference.
