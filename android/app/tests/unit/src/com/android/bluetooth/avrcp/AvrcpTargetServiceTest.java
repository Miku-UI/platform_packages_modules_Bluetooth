/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.bluetooth.avrcp;

import static com.google.common.truth.Truth.assertThat;

import android.net.Uri;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.audio_util.Image;
import com.android.bluetooth.audio_util.Metadata;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class AvrcpTargetServiceTest {

    private static final String TEST_DATA = "-1";

    @Test
    public void testQueueUpdateData() {
        List<Metadata> firstQueue = new ArrayList<Metadata>();
        List<Metadata> secondQueue = new ArrayList<Metadata>();

        firstQueue.add(createEmptyMetadata());
        secondQueue.add(createEmptyMetadata());
        assertThat(AvrcpTargetService.isQueueUpdated(firstQueue, secondQueue)).isFalse();

        secondQueue.add(createEmptyMetadata());
        assertThat(AvrcpTargetService.isQueueUpdated(firstQueue, secondQueue)).isTrue();

        firstQueue.add(createEmptyMetadata());
        firstQueue.get(1).album = TEST_DATA;
        firstQueue.get(1).genre = TEST_DATA;
        firstQueue.get(1).mediaId = TEST_DATA;
        firstQueue.get(1).trackNum = TEST_DATA;
        firstQueue.get(1).numTracks = TEST_DATA;
        firstQueue.get(1).duration = TEST_DATA;
        firstQueue.get(1).image =
                new Image(
                        InstrumentationRegistry.getInstrumentation().getTargetContext(), Uri.EMPTY);
        assertThat(AvrcpTargetService.isQueueUpdated(firstQueue, secondQueue)).isFalse();

        secondQueue.get(1).title = TEST_DATA;
        assertThat(AvrcpTargetService.isQueueUpdated(firstQueue, secondQueue)).isTrue();

        secondQueue.set(1, createEmptyMetadata());
        secondQueue.get(1).artist = TEST_DATA;
        assertThat(AvrcpTargetService.isQueueUpdated(firstQueue, secondQueue)).isTrue();
    }

    private Metadata createEmptyMetadata() {
        Metadata.Builder builder = new Metadata.Builder();
        return builder.useDefaults().build();
    }
}
