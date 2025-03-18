/*
 * Copyright 2019 The Android Open Source Project
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
#define LOG_TAG "bt_gd_shim"

#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>
#include <unistd.h>

#include <future>
#include <sstream>
#include <string>

#include "hal/snoop_logger.h"
#include "hci/acl_manager.h"
#include "hci/controller_interface.h"
#include "main/shim/entry.h"
#include "main/shim/stack.h"
#include "module.h"
#include "os/system_properties.h"
#include "os/wakelock_manager.h"
#include "shim/dumpsys.h"

namespace bluetooth {
namespace shim {

namespace {
constexpr char kModuleName[] = "shim::Dumpsys";
}  // namespace

struct Dumpsys::impl {
public:
  void DumpSync(int fd, std::promise<void> promise);
  int GetNumberOfBundledSchemas() const;

  impl(const Dumpsys& dumpsys_module);
  ~impl() = default;

private:
  void DumpAsync(int fd) const;

  const Dumpsys& dumpsys_module_;
};

const ModuleFactory Dumpsys::Factory =
        ModuleFactory([]() { return new Dumpsys(); });

Dumpsys::impl::impl(const Dumpsys& dumpsys_module)
    : dumpsys_module_(dumpsys_module) {}

void Dumpsys::impl::DumpAsync(int fd) const {
  const auto registry = dumpsys_module_.GetModuleRegistry();
  bluetooth::shim::GetController()->Dump(fd);
  bluetooth::shim::GetAclManager()->Dump(fd);
  bluetooth::os::WakelockManager::Get().Dump(fd);
  bluetooth::shim::GetSnoopLogger()->DumpSnoozLogToFile();
}

void Dumpsys::impl::DumpSync(int fd, std::promise<void> promise) {
  if (bluetooth::shim::Stack::GetInstance()->LockForDumpsys([=, *this]() {
        log::info("Started dumpsys procedure");
        this->DumpAsync(fd);
      })) {
    log::info("Successful dumpsys procedure");
  } else {
    log::info("Failed dumpsys procedure as stack was not longer active");
  }
  promise.set_value();
}

Dumpsys::Dumpsys() {}

void Dumpsys::Dump(int fd, const char** /*args*/, std::promise<void> promise) {
  if (fd <= 0) {
    promise.set_value();
    return;
  }
  CallOn(pimpl_.get(), &Dumpsys::impl::DumpSync, fd, std::move(promise));
}

/**
 * Module methods
 */
void Dumpsys::ListDependencies(ModuleList* /* list */) const {}

void Dumpsys::Start() { pimpl_ = std::make_unique<impl>(*this); }

void Dumpsys::Stop() { pimpl_.reset(); }

std::string Dumpsys::ToString() const { return kModuleName; }

}  // namespace shim
}  // namespace bluetooth
