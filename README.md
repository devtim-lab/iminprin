# iminprin

Aplikasi web print thermal 80mm untuk iMin D4 504 Pro (Android 13).

## Halaman
- `/` — Aplikasi Nomor Antrian (tiket thermal 80mm; print langsung tanpa dialog via app Android `iminprint_app`, fallback dialog print di browser)
- `/struk-demo.html` — Struk kasir demo 80mm

## Deploy ke Vercel
1. Push repo ini ke GitHub.
2. Buka https://vercel.com/new → Import repository `iminprin`.
3. Framework: **Other**, no build command. Deploy.

## Pemakaian di perangkat iMin
Buka URL hasil deploy di Chrome iMin → pilih printer "iMin" saat dialog print muncul (centang "set as default").
