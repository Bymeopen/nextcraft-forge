# NextCraft Forge

Aplikasi Android (varian chroot, butuh root) yang menjalankan Ubuntu 24.04 ARM64 untuk build dan agen coding di perangkat.
Varian tanpa root: [nextcraft-forge-proot](https://github.com/Bymeopen/nextcraft-forge-proot).

## Build

```bash
gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

## Dokumen

- `ARCHITECTURE.md`: keputusan arsitektur (ADR).
- `docs/`: rilis, uji perangkat, terminal, masalah runtime.

## Lisensi

GPL-3.0-only, lihat `LICENSE` dan `THIRD_PARTY_NOTICES.md` (library terminal Termux bersifat GPLv3, sehingga
distribusi APK wajib menyertakan source code). Copyright (C) 2026 Bymeopen.
