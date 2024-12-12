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
package com.android.bluetooth.vc;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.*;

import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.flags.Flags;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class VolumeControlInputDescriptorTest {

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Before
    public void setUp() throws Exception {
        // placeholder
    }

    @After
    public void tearDown() throws Exception {
        // placeholder
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_ADD_AICS_SUPPORT)
    public void testVolumeControlInputDescriptorInvalidIdOperations() throws Exception {
        VolumeControlInputDescriptor descriptor = new VolumeControlInputDescriptor();

        int invalidId = 1;
        int testGainValue = 100;
        int testGainMode = 1;
        boolean testGainMute = true;
        String testDesc = "testDescription";
        int testType = VolumeControlInputDescriptor.AUDIO_INPUT_TYPE_AMBIENT;
        int testGainSettingsMax = 100;
        int testGainSettingsMin = 0;
        int testGainSettingsUnit = 1;

        // Test adding all props using invalid ID
        assertThat(descriptor.isActive(invalidId)).isFalse();
        assertThat(descriptor.setActive(invalidId, true)).isFalse();
        assertThat(descriptor.setDescription(invalidId, testDesc)).isFalse();
        assertThat(descriptor.getDescription(invalidId)).isNull();
        assertThat(descriptor.setType(invalidId, testType)).isFalse();
        assertThat(descriptor.getType(invalidId))
                .isEqualTo(VolumeControlInputDescriptor.AUDIO_INPUT_TYPE_UNSPECIFIED);

        assertThat(descriptor.getGain(invalidId)).isEqualTo(0);
        assertThat(descriptor.isMuted(invalidId)).isFalse();
        assertThat(
                        descriptor.setPropSettings(
                                invalidId,
                                testGainSettingsUnit,
                                testGainSettingsMin,
                                testGainSettingsMax))
                .isFalse();
        assertThat(descriptor.setState(invalidId, testGainValue, testGainMode, testGainMute))
                .isFalse();
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_ADD_AICS_SUPPORT)
    public void testVolumeControlInputDescriptorMultipleInstanceAdded() throws Exception {

        VolumeControlInputDescriptor descriptor = new VolumeControlInputDescriptor();

        int validId = 10;

        // Verify that adding descriptor works increase descriptor size
        assertThat(descriptor.size()).isEqualTo(0);
        descriptor.add(validId);
        assertThat(descriptor.size()).isEqualTo(1);

        // Check if adding same id will not increase descriptor count.
        descriptor.add(validId);
        assertThat(descriptor.size()).isEqualTo(1);
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_ADD_AICS_SUPPORT)
    public void testVolumeControlInputDescriptorInstanceRemoveAndClear() throws Exception {

        VolumeControlInputDescriptor descriptor = new VolumeControlInputDescriptor();

        int id_1 = 10;
        int id_2 = 20;
        int invalidId = 1;

        // Verify that adding descriptor works increase descriptor size
        assertThat(descriptor.size()).isEqualTo(0);
        descriptor.add(id_1);
        assertThat(descriptor.size()).isEqualTo(1);
        descriptor.add(id_2);

        // Remove valid id
        descriptor.remove(id_1);
        assertThat(descriptor.size()).isEqualTo(1);

        // Remove invalid id not change number of descriptors
        descriptor.remove(invalidId);
        assertThat(descriptor.size()).isEqualTo(1);

        // Check clear API
        descriptor.clear();
        assertThat(descriptor.size()).isEqualTo(0);
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_ADD_AICS_SUPPORT)
    public void testVolumeControlInputDescriptorAllValidApiCalls() throws Exception {

        VolumeControlInputDescriptor descriptor = new VolumeControlInputDescriptor();

        int validId = 10;
        int testGainValue = 100;
        int testGainMode = 1;
        boolean testGainMute = true;
        String defaultDesc = "";
        String testDesc = "testDescription";
        int testType = VolumeControlInputDescriptor.AUDIO_INPUT_TYPE_AMBIENT;
        int testGainSettingsMax = 100;
        int testGainSettingsMin = 0;
        int testGainSettingsUnit = 1;

        descriptor.add(validId);

        // Active state
        assertThat(descriptor.isActive(validId)).isFalse();
        assertThat(descriptor.setActive(validId, true)).isTrue();
        assertThat(descriptor.isActive(validId)).isTrue();

        // Descriptor
        assertThat(descriptor.getDescription(validId)).isEqualTo(defaultDesc);
        assertThat(descriptor.setDescription(validId, testDesc)).isTrue();
        assertThat(descriptor.getDescription(validId)).isEqualTo(testDesc);

        // Type
        assertThat(descriptor.getType(validId))
                .isEqualTo(VolumeControlInputDescriptor.AUDIO_INPUT_TYPE_UNSPECIFIED);
        assertThat(descriptor.setType(validId, testType)).isTrue();
        assertThat(descriptor.getType(validId)).isEqualTo(testType);

        // Properties
        assertThat(
                        descriptor.setPropSettings(
                                validId,
                                testGainSettingsUnit,
                                testGainSettingsMin,
                                testGainSettingsMax))
                .isTrue();

        // State
        assertThat(descriptor.setState(validId, testGainValue, testGainMode, testGainMute))
                .isTrue();
        assertThat(descriptor.getGain(validId)).isEqualTo(testGainValue);
    }
}
