/*
 *  Copyright 2013 The WebRTC project authors. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

/* Modifications Copyright (C) 2026 Ettacent */

#ifndef PC_TEST_FAKE_RTC_CERTIFICATE_GENERATOR_H_
#define PC_TEST_FAKE_RTC_CERTIFICATE_GENERATOR_H_

#include <array>
#include <string>
#include <utility>

#include "absl/types/optional.h"
#include "api/peer_connection_interface.h"
#include "api/task_queue/task_queue_base.h"
#include "api/units/time_delta.h"
#include "rtc_base/rtc_certificate.h"
#include "rtc_base/rtc_certificate_generator.h"
#include "rtc_base/checks.h"

inline rtc::RTCCertificatePEM GenerateFakeCertificatePem(rtc::KeyType type) {
  const auto params = type == rtc::KT_RSA
                          ? rtc::KeyParams::RSA(1024, 0x10001)
                          : rtc::KeyParams::ECDSA();
  auto certificate =
      rtc::RTCCertificateGenerator::GenerateCertificate(params, absl::nullopt);
  RTC_CHECK(certificate);
  return certificate->ToPEM();
}

class FakeCertificatePems {
 public:
  explicit constexpr FakeCertificatePems(rtc::KeyType type) : type_(type) {}

  const rtc::RTCCertificatePEM& operator[](size_t index) const {
    RTC_CHECK_LT(index, 2u);
    if (type_ == rtc::KT_RSA) {
      static const std::array<rtc::RTCCertificatePEM, 2> pems = {
          GenerateFakeCertificatePem(rtc::KT_RSA),
          GenerateFakeCertificatePem(rtc::KT_RSA)};
      return pems[index];
    }
    static const std::array<rtc::RTCCertificatePEM, 2> pems = {
        GenerateFakeCertificatePem(rtc::KT_ECDSA),
        GenerateFakeCertificatePem(rtc::KT_ECDSA)};
    return pems[index];
  }

 private:
  const rtc::KeyType type_;
};

static constexpr FakeCertificatePems kRsaPems(rtc::KT_RSA);
static constexpr FakeCertificatePems kEcdsaPems(rtc::KT_ECDSA);

class FakeRTCCertificateGenerator
    : public rtc::RTCCertificateGeneratorInterface {
 public:
  FakeRTCCertificateGenerator() : should_fail_(false), should_wait_(false) {}

  void set_should_fail(bool should_fail) { should_fail_ = should_fail; }

  // If set to true, stalls the generation of the fake certificate until it is
  // set to false.
  void set_should_wait(bool should_wait) { should_wait_ = should_wait; }

  void use_original_key() { key_index_ = 0; }
  void use_alternate_key() { key_index_ = 1; }

  int generated_certificates() { return generated_certificates_; }
  int generated_failures() { return generated_failures_; }

  void GenerateCertificateAsync(const rtc::KeyParams& key_params,
                                const absl::optional<uint64_t>& expires_ms,
                                Callback callback) override {
    RTC_DCHECK(!expires_ms);

    // Only supports RSA-1024-0x10001 and ECDSA-P256.
    if (key_params.type() == rtc::KT_RSA) {
      RTC_DCHECK_EQ(key_params.rsa_params().mod_size, 1024);
      RTC_DCHECK_EQ(key_params.rsa_params().pub_exp, 0x10001);
    } else {
      RTC_DCHECK_EQ(key_params.type(), rtc::KT_ECDSA);
      RTC_DCHECK_EQ(key_params.ec_curve(), rtc::EC_NIST_P256);
    }
    rtc::KeyType key_type = key_params.type();
    webrtc::TaskQueueBase::Current()->PostTask(
        [this, key_type, callback = std::move(callback)]() mutable {
          GenerateCertificate(key_type, std::move(callback));
        });
  }

  static rtc::scoped_refptr<rtc::RTCCertificate> GenerateCertificate() {
    switch (rtc::KT_DEFAULT) {
      case rtc::KT_RSA:
        return rtc::RTCCertificate::FromPEM(kRsaPems[0]);
      case rtc::KT_ECDSA:
        return rtc::RTCCertificate::FromPEM(kEcdsaPems[0]);
      default:
        RTC_DCHECK_NOTREACHED();
        return nullptr;
    }
  }

 private:
  const rtc::RTCCertificatePEM& get_pem(const rtc::KeyType& key_type) const {
    switch (key_type) {
      case rtc::KT_RSA:
        return kRsaPems[key_index_];
      case rtc::KT_ECDSA:
        return kEcdsaPems[key_index_];
      default:
        RTC_DCHECK_NOTREACHED();
        return kEcdsaPems[key_index_];
    }
  }
  const std::string& get_key(const rtc::KeyType& key_type) const {
    return get_pem(key_type).private_key();
  }
  const std::string& get_cert(const rtc::KeyType& key_type) const {
    return get_pem(key_type).certificate();
  }

  void GenerateCertificate(rtc::KeyType key_type, Callback callback) {
    // If the certificate generation should be stalled, re-post this same
    // message to the queue with a small delay so as to wait in a loop until
    // set_should_wait(false) is called.
    if (should_wait_) {
      webrtc::TaskQueueBase::Current()->PostDelayedTask(
          [this, key_type, callback = std::move(callback)]() mutable {
            GenerateCertificate(key_type, std::move(callback));
          },
          webrtc::TimeDelta::Millis(1));
      return;
    }
    if (should_fail_) {
      ++generated_failures_;
      std::move(callback)(nullptr);
    } else {
      rtc::scoped_refptr<rtc::RTCCertificate> certificate =
          rtc::RTCCertificate::FromPEM(get_pem(key_type));
      RTC_DCHECK(certificate);
      ++generated_certificates_;
      std::move(callback)(std::move(certificate));
    }
  }

  bool should_fail_;
  bool should_wait_;
  int key_index_ = 0;
  int generated_certificates_ = 0;
  int generated_failures_ = 0;
};

#endif  // PC_TEST_FAKE_RTC_CERTIFICATE_GENERATOR_H_
