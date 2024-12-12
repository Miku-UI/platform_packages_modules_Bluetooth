

#include "common/init_flags.h"

#include <map>
#include <string>

namespace bluetooth {
namespace common {

bool InitFlags::logging_debug_enabled_for_all = false;
std::unordered_map<std::string, bool> InitFlags::logging_debug_explicit_tag_settings = {};
void InitFlags::Load(const char** flags) {}
void InitFlags::SetAll(bool value) { InitFlags::logging_debug_enabled_for_all = value; }

}  // namespace common
}  // namespace bluetooth
