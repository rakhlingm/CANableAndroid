# CANLink - Google Play Publication Guide

## App Info
- **Package:** team.night.canlink
- **App Name:** CANLink
- **Developer Account:** rakhlingm@gmail.com

---

## Step 1: Generate Signed AAB

In Android Studio:
1. **Build → Generate Signed Bundle / APK...**
2. Select **Android App Bundle** → Next
3. Create new keystore:
   - Key store path: `C:\keys\canlink.jks`
   - Password: (your password)
   - Alias: `canlink`
   - Validity: 25+ years
4. Select **release** → Create

**IMPORTANT:** Keep keystore file and passwords safe!

---

## Step 2: Google Play Console

1. Open https://play.google.com/console
2. Sign in with `rakhlingm@gmail.com`
3. Create app:
   - App name: `CANLink`
   - Default language: English (US)
   - App or game: App
   - Free or paid: Free

---

## Step 3: Store Listing

**Menu:** Расширение → Присутствие в магазине → Основная страница приложения

### Short description (80 chars max):
```
OBD-II diagnostics via CANable USB adapter. Speed, RPM, temperature.
```

### Full description:
```
CANLink connects your Android device to your vehicle's OBD-II port using a CANable USB adapter.

Features:
• Real-time vehicle speed (km/h or mph)
• Engine RPM monitoring
• Coolant temperature (°C or °F)
• Debug log for CAN bus data
• Auto-connect when adapter is plugged in

Requirements:
• CANable USB adapter
• USB OTG cable
• Vehicle with OBD-II port (1996+ for US, 2001+ for EU)

Simple, fast, no ads.
```

### Graphics (required):
| Type | Size | File |
|------|------|------|
| App icon | 512 x 512 px | `app/icon_preview.svg` → convert to PNG |
| Feature graphic | 1024 x 500 px | `app/feature_graphic.svg` → convert to PNG |
| Screenshots | min 2 | Take from device |

Convert SVG to PNG: https://svgtopng.com

---

## Step 4: Required Forms

**Menu:** Правила → Контент приложения

Fill all:
- [ ] Privacy policy URL
- [ ] Content rating questionnaire
- [ ] Target audience
- [ ] Data safety form

---

## Step 5: Testing (Required for new accounts)

Google requires **Closed Testing** before production:
- Minimum **12 testers** who install the app
- Minimum **14 days** of testing

### How to set up:
1. **Тестирование → Закрытое тестирование**
2. Create new release
3. Upload AAB file
4. Add 12+ tester emails
5. Wait 14 days
6. Then publish to production

### Testers only need to:
1. Accept invitation link
2. Install the app
That's it! No feedback required.

### Where to find testers:
- Family and friends
- Your other Google accounts
- Social media groups
- Reddit: r/androiddev, r/TestMyApp
- BetaFamily.com
- Fiverr

**Tip:** Add 15-20 emails in case some don't install.

---

## Step 6: Publish to Production

After 14 days with 12+ testers:
1. **Тестирование и выпуск → Рабочая версия**
2. Create new release
3. Upload AAB
4. Review and confirm

**Note:** After first app published, future apps can skip testing requirement.

---

## Project Files

- Icon SVG: `C:\src\CANableAndroid\app\icon_preview.svg`
- Feature graphic SVG: `C:\src\CANableAndroid\app\feature_graphic.svg`
- Source code: `C:\src\CANableAndroid\`

---

## Build Settings

- ProGuard/R8: Enabled for release
- Java version: 1.8
- Min SDK: 26
- Target SDK: 33
- Orientation: Portrait only
