/*
 * Copyright 2023 The Android Open Source Project
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

/*
 * Generated mock file from original source file
 *   Functions generated:13
 *
 *  mockcify.pl ver 0.7.1
 */

#include <cstdint>
#include <functional>

// Original included files, if any
#include <cstring>

// Original usings

// Mocked compile conditionals, if any

namespace test {
namespace mock {
namespace btif_sock_l2cap {

// Shared state between mocked functions and tests
// Name: on_btsocket_l2cap_close
// Params: uint64_t socket_id
// Return: void
struct on_btsocket_l2cap_close {
  std::function<void(uint64_t socket_id)> body{[](uint64_t /* socket_id */) {}};
  void operator()(uint64_t socket_id) { body(socket_id); }
};
extern struct on_btsocket_l2cap_close on_btsocket_l2cap_close;

// Name: on_btsocket_l2cap_opened_complete
// Params: uint64_t socket_id, bool success
// Return: void
struct on_btsocket_l2cap_opened_complete {
  std::function<void(uint64_t socket_id, bool success)> body{
          [](uint64_t /* socket_id */, bool /* success */) {}};
  void operator()(uint64_t socket_id, bool success) { body(socket_id, success); }
};
extern struct on_btsocket_l2cap_opened_complete on_btsocket_l2cap_opened_complete;

}  // namespace btif_sock_l2cap
}  // namespace mock
}  // namespace test

// END mockcify generation
