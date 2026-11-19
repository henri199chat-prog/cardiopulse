# CardioPulse — نبض وضغط (Android / Kotlin / Jetpack Compose)

تطبيق أندرويد ذكي وعالي الدقة لمتابعة صحة القلب والأوعية الدموية باللغتين **العربية (RTL)** و**الفرنسية (LTR)**.

Application Android native haute précision pour le suivi cardiaque (**Fréquence cardiaque & VFC par PPG**) et la **Tension Artérielle certifiée (Bluetooth BLE GATT 0x1810 + Suivi clinique ESC/AHA)**.

---

## المزايا العلمية والتقنية / Architecture & Fiabilité

### 1. قياس دقات نبض القلب وتقلب النبض عبر الكاميرا (`core/ppg`)
- **CameraX + Flash LED (30 FPS)** مع قراءة الطابع الزمني الصلب (`ImageInfo.timestamp`) بدقة النانوثانية وتحليل المنطقة المركزية (ROI 50%×50%) من `YUV_420_888`.
- **كشف ملامسة الإصبع (`FingerPresenceDetector`)**: يميز بين غياب الإصبع، تسرب الضوء المحيط، والضغط المفرط الذي يكتم الشعيرات الدموية.
- **رصد حركة اليد (`MotionMonitor`)**: يستخدم حساس التسارع الخطي لرفض الإشارات المشوشة بالحركة.
- **معالجة إشارة طبية (`PpgSignalProcessor` & `BiquadFilter`)**:
  - إعادة أخذ عينات منتظمة (`30 Hz`) + إزالة انجراف التنفس (`Moving Baseline Detrending`).
  - مرشح Butterworth صفري الطور (`Zero-Phase filtfilt`، نطاق `0.65 – 3.5 Hz` الموافق لـ `39 – 210 BPM`).
  - **التحقق المزدوج (Dual-Method Cross-Validation)**: يقارن بين تحليل القمم الزمني (مع قمع الموجة الديكروتية وتنقيح قطعي مكافئ لحساب **HRV: RMSSD / SDNN / pNN50**) وبين التحليل الطيفي (`Hann-Windowed DFT / FFT`).
  - **سياسة عدم التخمين**: يرفض التطبيق عرض أي رقم عشوائي إذا انخفضت جودة الإشارة (`SQI`)، أو زادت الحركة، أو اختلف التحليلان بأكثر من `4.5 BPM`.

### 2. قياس ضغط الدم بدقة حقيقية (`core/bp`)
- **شفافية طبية**: لا يولّد التطبيق قراءات ضغط دم وهمية من الكاميرا وحدها.
- **دعم Bluetooth BLE الطبي القياسي (`BleBloodPressureManager` & `BleBloodPressureParser`)**:
  - متوافق مع المعيار العالمي **Bluetooth SIG GATT Blood Pressure Service (`0x1810`)**:
    - `0x2A35` (Blood Pressure Measurement — مع فك ترميز **IEEE-11073 16-bit SFLOAT** بالـ `mmHg` و`kPa`).
    - `0x2A36` (Intermediate Cuff Pressure — عرض ضغط السوار لحظيًا أثناء النفخ).
    - `0x2A37` (Heart Rate Measurement + RR intervals).
  - قراءة تنبيهات الجهاز الطبية تلقائيًا: **حركة الجسم**، **ارتخاء السوار**، و**عدم انتظام النبض**.
  - متوافق مع أجهزة القياس المعتمدة مثل **Omron, Beurer, Withings, Microlife, Rossmax, Yuwell, A&D**.
- **التصنيف الطبي الذكي (`BloodPressureClassifier`)**:
  - يدعم معياري **ESC/ESH (الأوروبي)** و**AHA/ACC (الأمريكي)**، مع حساب **الضغط الشرياني الوسطي (MAP)** و**ضغط النبض (PP)**، وتنبيه طوارئ فوري لأزمات الضغط (`≥ 180/120`).
  - وضع **متوسط قراءتين** وفق توصية أطباء القلب + مؤقت راحة **5 دقائق** + قائمة التحقق السريرية.

### 3. التحليلات الذكية وتقارير الطبيب (`core/analytics` & `core/export`)
- متوسطات آخر 7 أيام، ومقارنة **ضغط الصباح مقابل المساء** (رصد ارتفاع الضغط الصباحي `Morning Surge`)، وتنبيهات اتساع ضغط النبض أو عدم انتظام الإيقاع.
- تصدير **تقرير PDF طبي جاهز للطبيب** (مع منحنى الضغط وملخص المؤشرات) وملف **CSV** متوافق مع Excel.
- تخزين محلي 100% عبر **Room Database** و**DataStore** دون رفع أي بيانات أو صور للإنترنت.

---

## التشغيل والبناء / Build & Tests

```bash
# Run all biomedical DSP, BLE IEEE-11073, Clinical Classifier & Analytics unit tests
./gradlew testDebugUnitTest

# Build installable Android Debug APK
./gradlew assembleDebug
```

ملف الـ APK الناتج يكون في:
`app/build/outputs/apk/debug/app-debug.apk`
