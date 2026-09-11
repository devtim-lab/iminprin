# iMin Print App (WebView + IminPrinterSDK)

Aplikasi Android untuk iMin D4 504 Pro: menampilkan web app Anda fullscreen
dan mencetak thermal 80mm TANPA dialog print.

## Cara kerja
1. Pertama kali jalan, masukkan URL web app (misal https://iminprin.vercel.app), simpan.
2. Web app Anda tampil fullscreen seperti native app.
3. Dari JavaScript web, panggil print langsung:

   ```javascript
   Android.printTicket(document.getElementById('tiket').outerHTML);
   ```

   HTML elemen #tiket dirender jadi bitmap 300dpi (lebar 945px = 80mm),
   dicetak ke printer built-in, auto-cut. Tanpa dialog, tanpa sentuh.

## Setup di web (Vercel)
- Ganti semua `window.print()` menjadi `Android.printTicket(...)`.
- Deteksi environment agar tetap jalan di browser biasa:

  ```javascript
  function cetak(el) {
    if (typeof Android !== 'undefined') {
      Android.printTicket(el.outerHTML);
    } else {
      window.print();
    }
  }
  ```

## Build APK
1. Buka folder ini di Android Studio.
2. `./gradlew assembleDebug` → APK di `app/build/outputs/apk/debug/`
3. Install ke iMin D4, jalankan, set URL sekali.
