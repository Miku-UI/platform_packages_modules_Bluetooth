// Copyright 2024, The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

//! HCI Proxy module implementation, along with
//! reading / writing helpers of Bluetooth HCI Commands, Events and Data encapsulations.

use std::sync::Arc;

/// Interface for building a module
pub trait ModuleBuilder: Send + Sync {
    /// Build the module from the next module in the chain
    fn build(&self, next_module: Arc<dyn Module>) -> Arc<dyn Module>;
}

/// Interface of a an HCI proxy module
pub trait Module: Send + Sync {
    /// Returns the next chained proxy module
    fn next(&self) -> &dyn Module;

    /// HCI Command from Host to Controller
    fn out_cmd(&self, data: &[u8]) {
        self.next().out_cmd(data);
    }
    /// ACL Data from Host to Controller
    fn out_acl(&self, data: &[u8]) {
        self.next().out_acl(data);
    }
    /// SCO Data from Host to Controller
    fn out_sco(&self, data: &[u8]) {
        self.next().out_sco(data);
    }
    /// ISO Data from Host to Controller
    fn out_iso(&self, data: &[u8]) {
        self.next().out_iso(data);
    }

    /// HCI Command from Controller to Host
    fn in_evt(&self, data: &[u8]) {
        self.next().in_evt(data);
    }
    /// ACL Data from Controller to Host
    fn in_acl(&self, data: &[u8]) {
        self.next().in_acl(data);
    }
    /// SCO Data from Controller to Host
    fn in_sco(&self, data: &[u8]) {
        self.next().in_sco(data);
    }
    /// ISO Data from Controller to Host
    fn in_iso(&self, data: &[u8]) {
        self.next().in_iso(data);
    }
}

use bluetooth_offload_hci_derive as derive;

mod command;
mod data;
mod event;
mod reader;
mod status;
mod writer;

pub use command::*;
pub use data::*;
pub use event::*;
pub use status::*;
