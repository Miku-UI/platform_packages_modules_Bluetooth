/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.bluetooth.le_scan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Helper class to keep track of MSFT patterns, their filter index, and number of monitors
 * registered with that pattern. Some chipsets don't support multiple monitors with the same
 * pattern. To solve that and to generally ease their task, we merge monitors with the same pattern,
 * so those monitors will only be sent once.
 */
class MsftAdvMonitorMergedPatternList {
    static class MsftAdvMonitorMergedPattern {
        private final MsftAdvMonitor.Pattern[] mPatterns;
        private final int mFilterIndex;
        private int mCount = 0;

        MsftAdvMonitorMergedPattern(MsftAdvMonitor.Pattern[] pattern, int filterIndex) {
            mPatterns = pattern;
            mFilterIndex = filterIndex;
        }
    }

    List<MsftAdvMonitorMergedPattern> mMergedPatterns = new ArrayList<>();

    // Two patterns are considered equal if they have the exact same pattern
    // in the same order. Therefore A+B and B+A are considered different, as
    // well as A and A+A. This shouldn't causes issues but could be optimized.
    // Returns merged pattern or null if not found.
    private MsftAdvMonitorMergedPattern getMergedPattern(MsftAdvMonitor.Pattern[] pattern) {
        return mMergedPatterns.stream()
                .filter(mergedPattern -> Arrays.equals(mergedPattern.mPatterns, pattern))
                .findFirst()
                .orElse(null);
    }

    // If pattern doesn't exist, creates new entry with given index.
    // If pattern exists, increases count and returns filter index.
    int add(int filterIndex, MsftAdvMonitor.Pattern[] pattern) {
        MsftAdvMonitorMergedPattern mergedPattern = (getMergedPattern(pattern));
        if (mergedPattern == null) {
            mergedPattern = new MsftAdvMonitorMergedPattern(pattern, filterIndex);
            mMergedPatterns.add(mergedPattern);
        }

        mergedPattern.mCount++;
        return mergedPattern.mFilterIndex;
    }

    // If pattern exists, decreases count. If count is 0, removes entry.
    // Returns true if there are no more instances of the given filter index
    boolean remove(int filterIndex) {
        mMergedPatterns.stream()
                .filter(pattern -> pattern.mFilterIndex == filterIndex)
                .forEach(pattern -> pattern.mCount--);
        return mMergedPatterns.removeIf(pattern -> pattern.mCount == 0);
    }
}
