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

#pragma once

/*******************************************************************************
 *
 * Function         l2c_init
 *
 * Description      This function is called once at startup to initialize
 *                  all the L2CAP structures
 *
 * Returns          void
 *
 ******************************************************************************/
void l2c_init();

/*******************************************************************************
 *
 * Function         l2c_free
 *
 * Description      This function is called once at shutdown to free and
 *                  clean up all the L2CAP structures
 *
 * Returns          void
 *
 ******************************************************************************/
void l2c_free();

/*******************************************************************************
**
** Function         L2CA_Dumpsys
**
** Description      This function provides dumpsys data during the dumpsys
**                  procedure.
**
** Parameters:      fd: Descriptor used to write the L2CAP internals
**
** Returns          void
**
*******************************************************************************/
void L2CA_Dumpsys(int fd);
