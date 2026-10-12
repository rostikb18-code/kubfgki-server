# Public Brawl Stars detection datasets — source audit

Checked on 2026-10-12. These are candidate sources, not a claim that their contents have been downloaded, audited locally, or trained by RostikAI.

| Source | Public page reports | Classes / notes | Link |
|---|---|---|---|
| Bloxxy — Brawl Stars Dataset | 2,551 images; 12 versions; MIT listed | 10 classes: Ball, Enemy, Friendly, Gem, Hot_Zone, Me, PP, PP_Box, Safe_Enemy, Safe_Friendly. This is not a general-purpose map/health/ammo/bush label set. | https://universe.roboflow.com/bloxxy/brawl-stars-dataset |
| workspxce — Brawl Stars Dataset | 2,551 images; 2 versions; MIT listed | Same 10 class names shown on the page. The page shows hosted model metrics, which are not a guarantee of performance on a different split or on-device. | https://universe.roboflow.com/workspxce/brawl-stars-dataset-bifks |
| yolo-ifkzx — brawl stars | 998 images; CC BY 4.0 listed | Page's class display is ambiguous/inconsistent; inspect downloaded YAML and labels before using. | https://universe.roboflow.com/yolo-ifkzx/brawl-stars-7ybb6 |
| bobo — brawl stars | 151 images; MIT listed | Classes include player, attack, enemy, gem, powercube, team. | https://universe.roboflow.com/bobo-mvmtk/brawl-stars-xxomh |
| bsv — Brawl Stars brawler images | Version history; MIT listed on the page | Appears focused on brawler/character imagery, not full-match tactical scene understanding. | https://universe.roboflow.com/bsv/brawl-stars-vtq0k/dataset/5 |

## Important caveats

- The user-mentioned “Fabsen” projects and image counts were not independently confirmed by the search results available during this audit. Do not treat that attribution/count as verified.
- Dataset cards can change, and class names, annotation quality, versions, and licenses must be confirmed on the exact version downloaded.
- A class called `Enemy` does not necessarily identify a specific brawler, team relation, or health state. A box dataset cannot train pixel-level terrain segmentation or a tactical policy by itself.
- For train/validation/test separation, prefer different matches or videos in each split. Randomly splitting near-adjacent frames creates leakage and misleading metrics.
- Download through the dataset owner's current supported workflow. Do not commit API keys, tokens, or a large downloaded dataset into the application repository.
- Respect each dataset's license and the game's content terms; preserve attribution where required.

The detector-training script in this directory accepts an exported YOLO-format dataset and produces metrics, a model file, an ONNX export and a report. It does not silently choose a dataset or claim the results are valid without evaluating them.
