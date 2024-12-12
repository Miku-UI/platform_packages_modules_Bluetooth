#pragma once
#include <memory>

struct alarm_t;

class AlarmMock {
public:
  MOCK_METHOD1(AlarmNew, alarm_t*(const char*));
  MOCK_METHOD1(AlarmFree, void(alarm_t*));
  MOCK_METHOD1(AlarmCancel, void(alarm_t*));
  MOCK_METHOD4(AlarmSet,
               void(alarm_t* alarm, uint64_t interval_ms, alarm_callback_t cb, void* data));
  MOCK_METHOD4(AlarmSetOnMloop,
               void(alarm_t* alarm, uint64_t interval_ms, alarm_callback_t cb, void* data));
  MOCK_METHOD1(AlarmIsScheduled, bool(const alarm_t*));

  static inline AlarmMock* Get() {
    if (!localAlarmMock) {
      localAlarmMock = std::make_unique<AlarmMock>();
    }
    return localAlarmMock.get();
  }

  static inline void Reset() { localAlarmMock = std::make_unique<AlarmMock>(); }

private:
  static std::unique_ptr<AlarmMock> localAlarmMock;
};

std::unique_ptr<AlarmMock> AlarmMock::localAlarmMock;

alarm_t* alarm_new(const char* name) { return AlarmMock::Get()->AlarmNew(name); }

void alarm_free(alarm_t* alarm) { AlarmMock::Get()->AlarmFree(alarm); }

void alarm_set_on_mloop(alarm_t* alarm, uint64_t interval_ms, alarm_callback_t cb, void* data) {
  AlarmMock::Get()->AlarmSetOnMloop(alarm, interval_ms, cb, data);
}

void alarm_set(alarm_t* alarm, uint64_t interval_ms, alarm_callback_t cb, void* data) {
  AlarmMock::Get()->AlarmSet(alarm, interval_ms, cb, data);
}

bool alarm_is_scheduled(const alarm_t* alarm) { return AlarmMock::Get()->AlarmIsScheduled(alarm); }

void alarm_cancel(alarm_t* alarm) { AlarmMock::Get()->AlarmCancel(alarm); }
