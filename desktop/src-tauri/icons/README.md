# App icons

The icons listed in `tauri.conf.json` (`bundle.icon`), plus `icon.png`, which Linux uses. They were made from the
logo in `.github/assets/logo.svg`, rendered at 1024×1024.

To change them, render the new logo as a square PNG of at least 1024×1024 and run this in `desktop/`:

```powershell
npx tauri icon path\to\logo.png
```

The command also writes Android, iOS and Windows Store sizes, which this app doesn't use; delete them.
