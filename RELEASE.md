# Release 1.0.0

## شناسه نسخه

- Application ID: `com.waxew.hesabdar`
- Version Name: `1.0.0`
- Version Code: `7`
- Room Schema: `6`

## شرایط انتشار

نسخه 1.0.0 زمانی قابل انتشار است که:

- Unit Testهای پروژه موفق باشند.
- `assembleDebug` بدون خطا ساخته شود.
- Migrationهای Room مخرب نباشند.
- هیچ Keystore یا رمز خصوصی داخل Git ثبت نشده باشد.
- برای APK عمومی/Publish، چهار Secret امضای ثابت در GitHub Actions تنظیم شده باشند و `assembleRelease` با موفقیت اجرا شود.

## CI

Workflow `.github/workflows/android.yml` روی `main`، `develop-v1` و `release-v1` اجرا می‌شود. ترتیب اصلی:

1. Checkout
2. Java 17
3. Gradle 8.9
4. Unit Tests
5. Debug APK
6. Upload Debug Artifact
7. در صورت وجود Secrets: ساخت و Upload نسخه Release امضاشده

## Artifactها

- تست نصب و QA: `App-Hesabdar-v1.0.0-debug`
- انتشار عمومی فقط با امضای ثابت: `App-Hesabdar-v1.0.0-release`

## Secrets موردنیاز برای Release

- `HESABDAR_RELEASE_KEYSTORE_BASE64`
- `HESABDAR_RELEASE_KEYSTORE_PASSWORD`
- `HESABDAR_RELEASE_KEY_ALIAS`
- `HESABDAR_RELEASE_KEY_PASSWORD`

Keystore باید ثابت بماند؛ ساخت Keystore جدید برای نسخه بعدی باعث می‌شود APK جدید به‌عنوان Update نسخه قبلی قابل نصب نباشد.

## QA اصلی نسخه 1.0

- نصب تازه برنامه.
- ساخت طرف‌حساب.
- ساخت کالا با موجودی افتتاحیه و مشاهده `OPENING` در کارتکس.
- ساخت خدمت و اطمینان از نداشتن گردش انبار.
- ساخت صندوق و حساب بانک.
- فروش نقدی با انتخاب صندوق و کنترل افزایش مانده خزانه.
- فروش نسیه و کنترل مطالبات.
- خرید با انتخاب بانک و کنترل کاهش مانده بانک.
- دریافت/پرداخت مستقل و کنترل سند دوبل.
- فروش/خرید چندردیفی با تخفیف/مالیات/حمل.
- ابطال فاکتور و کنترل Reversal موجودی، وجه و سند.
- ایجاد چک و قسط و یادآوری.
- Backup محلی و HDBX رمزگذاری‌شده.
- Restore با رمز صحیح و رد رمز اشتباه.
- کلید Back در Drawer، صفحه شخص و جزئیات کالا.
- خروجی PDF/CSV.
- حالت روشن/تیره.

## محدودیت‌های برنامه‌ریزی‌شده برای نسخه‌های بعد

Payment Allocation چندفاکتوری، چندانبار، Multi-Unit پیشرفته، اسکن دوربین، Weighted Average/FIFO کامل، بستن دوره حسابداری پیشرفته، چاپ حرارتی و Sync ابری جزو توسعه‌های بعد از هسته پایدار 1.0 هستند.
