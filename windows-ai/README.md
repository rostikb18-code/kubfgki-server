# RostikAI Windows Emulator Prototype

This folder contains the first Windows integration path for the project. The native Android APK is still separate.

## Option A — run the existing RostikAI test scaffold

Use Python 3.11 on Windows:

```powershell
py -3.11 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
python -m unittest discover -s tests -v
```

Read-only ADB screenshot test:

1. Start an Android emulator and open the game or home screen.
2. Install Android SDK Platform Tools and ensure `adb.exe` is on PATH.
3. Run `adb devices` and confirm the emulator is listed as `device`.
4. Run `python capture_once.py --serial emulator-5554 --output captures/latest.png` (replace the serial with yours).

This scaffold does not yet detect objects or send game inputs.

## Option B — launch an existing community game-playing bot (Windows / BlueStacks)

I added `run_upstream_bot.py`, which checks out a pinned revision of [Jooi025/BrawlStarsBot](https://github.com/Jooi025/BrawlStarsBot), verifies the repository MIT license notice, checks for its bundled OpenVINO model files, and can launch its own controller. It keeps that project isolated under `windows-ai/vendor/BrawlStarsBot` instead of silently mixing incompatible source into the Android app.

Prerequisites: Windows, Git, Python 3.11, BlueStacks with Brawl Stars running, and enough disk/network capacity to install its dependencies. From this directory:

```powershell
py -3.11 -m venv .venv-upstream
.\.venv-upstream\Scripts\Activate.ps1
python run_upstream_bot.py --setup-only
python run_upstream_bot.py --install
```

For the convenience launcher, double-click `START-UPSTREAM-BOT.bat`; it creates the virtual environment, fetches/verifies the pinned source and installs missing dependencies. It then opens the upstream bot menu. For manual setup, after dependencies install, configure the upstream project's `constants.py` for your BlueStacks window and selected brawler, then launch with:

```powershell
python run_upstream_bot.py
```

**Important limitations and risk:** this upstream bot is designed for a visible BlueStacks desktop window, not generic ADB input and not the RostikAI Android APK. Its README documents known detection and gameplay limitations. It sends real mouse/keyboard input; use only in an environment/account where you accept the risk of lost trophies or enforcement. The bootstrap pins source code, but the existence check is not proof of model accuracy, compatibility with every emulator/resolution, or a separate license grant for every bundled asset. Stop immediately if detections or actions are wrong.

## CI and tests

Run `python -m unittest discover -s tests -v`. GitHub Actions runs the unit tests on Windows/Python 3.11. The tests validate the scaffold only; they do not validate gameplay.

See [THIRD_PARTY_AUDIT.md](THIRD_PARTY_AUDIT.md) for the source/license decision.
