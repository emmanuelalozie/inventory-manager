# App icons

This folder needs the icon files listed in `tauri.conf.json` (`bundle.icon`):

- `32x32.png`
- `128x128.png`
- `128x128@2x.png`
- `icon.icns`
- `icon.ico`

They are binary files, so they are not checked in. On Windows, Tauri needs `icon.ico` even for
`npm run dev`, so the build fails until you create them.

Generate all of them from one square PNG (at least 1024×1024, ideally with a transparent background).
Run this in PowerShell from the `desktop\` folder:

```powershell
npx tauri icon path\to\logo.png
```

The command writes the icons into this folder (`src-tauri\icons\`), plus a few extra sizes you can keep or delete.
