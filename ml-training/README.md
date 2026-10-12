# RostikAI — Brawl Stars detector training

This directory adds a reproducible **detector-training workflow**, not pre-trained weights. It does not download private Roboflow projects or embed API keys. Dataset availability, labels, licenses and export compatibility must be checked for each source.

## 1. Prepare a dataset

Use a dataset you are authorized to download. In Roboflow, export it in **YOLOv8/Ultralytics** format and unpack it locally. Preserve the dataset's license and attribution. Do not assume every project labelled “Brawl Stars” has compatible classes: inspect its class names and labels first.

Expected layout:

```
dataset/
  data.yaml
  train/images/...
  train/labels/...
  valid/images/...
  valid/labels/...
  test/images/...       # strongly recommended, held out until final evaluation
  test/labels/...
```

Example `data.yaml` (replace class names with the dataset's actual class list; IDs must match label files):

```yaml
path: /absolute/path/to/dataset
train: train/images
val: valid/images
test: test/images
names:
  0: brawler
  1: crate
```

Never combine datasets until their class IDs and annotation conventions have been reconciled. Split by match/video/session where possible, not random adjacent frames, to avoid near-duplicate leakage between train and validation.

## 2. Create a Python environment

Python 3.10–3.12 is recommended for broad PyTorch/Ultralytics compatibility. Install a PyTorch build appropriate for your machine first if you want NVIDIA CUDA, then:

```bash
python -m venv .venv
# Windows PowerShell:
.\.venv\Scripts\Activate.ps1
# Linux/macOS:
# source .venv/bin/activate
python -m pip install --upgrade pip
pip install -r requirements.txt
```

## 3. Audit annotations before training

```bash
python audit_yolo_dataset.py --data /path/to/dataset/data.yaml
```

This checks split paths, image/label pairs, class-ID ranges, malformed boxes, empty-label counts and duplicate image content. Fix warnings that indicate data leakage or annotation mistakes before training.

## 4. Train, evaluate, export

```bash
python train_detector.py --data /path/to/dataset/data.yaml --model yolo11n.pt --epochs 100 --imgsz 640 --device 0
```

Use `--device cpu` if no CUDA GPU is available; training will be slower. The script runs validation, evaluates the configured test split when present, exports ONNX, and writes a run report. It deliberately does **not** copy the resulting model into Android's production assets automatically.

Before deployment, inspect precision, recall, mAP50 and mAP50-95 overall and per class, test on entirely unseen matches/maps, compare ONNX predictions with the source model, measure latency on the target phone, and create a model card with provenance, preprocessing, labels, output tensor contract and SHA-256. A successful export is not proof of useful detections.

## Scope and limits

- A detector learns only the labels supplied. If the dataset lacks health, ammo, bushes, gas or safe-zone annotations, it cannot learn them from bounding boxes for unrelated classes.
- Bounding boxes do not teach movement or tactics. Start with a rule-based controller only after detections are measured and stable; keep actions disabled while perception confidence is low.
- This pipeline trains a **single object detector**. It does not produce the five separate model roles listed in the Android model registry, and it does not train a tactical policy.
- Roboflow project counts and class lists change. Confirm current versions, download terms and license on the project's own page before using the data.
- Ultralytics licensing may impose obligations depending on how the software/model is used or distributed; review the current license before distributing a trained model or app.

See also the Android model contract at `android-native/app/src/main/assets/models/README.md`.
