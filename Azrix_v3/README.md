# Azrix_v2 — Azrix Jr life app

Health, personal development, wealth and savings in one offline Android app.
Same shape as the manual builder: a thin WebView shell around one HTML file in
`app/src/main/assets/index.html`, with a Java class that does nothing but
display it and pass the back button through.

No `INTERNET` permission is declared (the one ML Kit asks for is stripped in the
manifest), so the app cannot reach the network at all. Everything you record
stays in the WebView's local storage on the phone.

- Package: `com.azrix.life` (the manual builder is `com.azrix.jr`, so both
  install side by side)
- Min Android: 7.0 (API 24) · Target: Android 14 (API 34)
- Size: roughly 12 MB (most of it is the offline text reader)

## New in 2.1

- **Scan / import transactions** — Wealth → *Import*, or *Scan or import* on
  the + button. Take a photo, pick a screenshot (bank app, PayNow, GPay) or a
  PDF e-statement, including password-protected ones. The text is read on the
  phone (Google ML Kit, bundled model; PDFs through pdf.js) and every line comes
  back for review: description, date, **In / Out**, amount and a **category**
  you choose. Statements are checked against their running balance, re-imports
  are flagged as duplicates, and the category you pick for a payee is
  remembered next time.
- **Your own categories** — Settings → Categories: add, rename, recolour or
  delete money-out and money-in categories (deleted ones move to "Other").
- **Profile photo** — Settings → Profile; it replaces the settings icon in the
  top corner.
- **Daily notes** — at the bottom of Home, one page per day with *All notes*
  to search and read back.
- **Savings chart fix** — no more 20K target line before you have set one.

## What is in it

**Home** — no title bar: the Azrix Jr logo is the header. It draws itself in
when you arrive, the gradient drifts slowly, and three rings of your choice sweep
up to today's numbers. Below: habits to tick off, what you have spent today
against your plan's daily pace, your closest savings goal, and the day's focus.
Early in a new month a card offers the finished month's plan-vs-actual review.

**Health** — weight and BMI with a trend chart; water, sleep and steps against
your targets; fourteen days of sleep and steps; workouts; blood pressure, pulse
and sugar. **Customize health** turns any section on or off, sets the water
glass size, edits the workout types and picks the three Home rings. **My
trackers** measures anything else — as a number (set the day's value or add to
it), a yes/no, or a 1–5 rating, with an optional daily target. Presets include
meditation, mood, energy, vitamins, blood oxygen, waist, push-ups, fruit & veg,
caffeine, screen time and body fat.

**Growth** — habits with streaks and a fourteen-day grid, goals broken into
dated steps, books and courses with progress, and a short daily journal.

**Wealth** — rupees and Singapore dollars are kept in **separate books**, each
with its own entries, totals, charts, accounts and budget. Switch between ₹, S$
and an "All" view that converts into your main currency. Every money entry
carries an explicit ₹ / S$ switch.

**Budget plan** — for any month and book, plan the income you expect and what
you mean to spend per category (or start from last month's plan or last month's
actual spending). During the month you see spent against plan, what is left, a
safe amount per day and each category's progress. Once the month ends it turns
into a **plan vs executed** review: the verdict, planned / executed / difference,
income and savings planned against actual, each category side by side, and the
biggest overrun and saving. A six-month chart compares plan and execution over
time.

**Savings** — goals with progress and what you need to put aside each month to
land on time, target against actual by month, and an EMI tracker.

**Look & feel** (palette button, top right) — System / Light / Dark, eleven
colour gradients or your own two colours, and an animations switch. The rings,
buttons, meters, tab bar and the logo itself all follow the gradient.

The exchange rate for the "All" view is set in Settings; the app is offline, so
it is whatever you last typed.

## Three ways to get the APK

### 1. GitHub Actions (no tools to install)

1. Create a new GitHub repository.
2. Upload this whole folder to it and push to `main`.
3. Open the **Actions** tab. The *Build APK* workflow runs by itself.
4. When it finishes, download `Azrix_v2.apk` from the **Releases** list on the
   repository page (or `Azrix_v2-apk` from the run's Artifacts).
5. Copy the `.apk` to your phone and open it. Android will ask you to allow
   installs from that source — that is expected for an app you built yourself.

> Dragging a folder into GitHub's upload box quietly skips names that begin with
> a dot, which means `.github/` and `.gitignore` do not make it across and no
> build ever starts. If the Actions tab is empty, add the workflow by hand:
> **Add file → Create new file**, type `.github/workflows/build-apk.yml` as the
> name, paste the contents of that file from this folder, and commit.

### 2. Android Studio

1. **File → Open** and pick this folder.
2. Let it sync (it downloads Gradle and the SDK bits it needs).
3. **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
4. The APK lands in `app/build/outputs/apk/debug/`.

Every build is signed with `app/azrix-release.jks`, so each new APK installs
over the previous one and keeps your entries. Keep that file — Android refuses
to update an app signed with a different key — and keep the repository private
while it is in there.

### 3. Command line

Needs JDK 17 and the Android SDK, with `ANDROID_HOME` set:

```bash
gradle wrapper          # first time only, creates ./gradlew
./gradlew assembleRelease
```

## Changing the app

Everything is `app/src/main/assets/index.html` — layout, colours, screens,
charts. Edit it, rebuild, done; no Java changes needed. It opens in any desktop
browser on its own, which is the quickest way to try a change before building.

Android-only parts (camera, file picker, text reading, profile photo) live in
`MainActivity.java` behind `window.AzrixNative`; in a desktop browser the page
falls back to a file chooser and reads PDFs only.

The brand colours are the two Azrix blues (`#1CADE3` and `#2483C5`) defined at
the top of the file, and the launcher icons under `app/src/main/res/mipmap-*/`
are the same bolt used by the manual builder.

## Your data

Records live in this app's own storage on the phone. Uninstalling clears it, so
take a backup now and then: **Settings → Copy backup** gives you a block of text
to keep anywhere, and **Restore from backup** puts it back. The same text moves
your records to another phone.

**Coming from 2.0:** the earlier APKs were signed with a throwaway key, so 2.1
cannot install over them. Once only: Settings → Copy backup, keep the text,
uninstall the old app, install 2.1, then Restore from backup. From 2.1 on,
updates install over the top.

## A note on the screen lock

The code lock in Settings keeps the app behind four to six digits if someone
picks up your unlocked phone. It is not encryption, and it will not stop anyone
with real access to the device. Treat it as a curtain, not a safe.

## A note on the health screens

Weight, sleep, blood pressure, pulse and sugar here are your own notes, kept for
your own use. They are not a medical record and nothing in the app is medical
advice — anything that worries you is worth a word with your doctor.
