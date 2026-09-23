#!/usr/bin/env bash
set -euo pipefail

# Optional local research runtime. Stems and model weights stay outside the repository.
APP_DATA="${HOME}/.local/share/intonation-trainer"
uv venv --python python3.10 "${APP_DATA}/python"
uv pip install --python "${APP_DATA}/python/bin/python" torch \
  --index-url https://download.pytorch.org/whl/cpu
uv pip install --python "${APP_DATA}/python/bin/python" \
  'audio-separator==0.47.0' 'onnxruntime==1.23.2'
uv pip install --python "${APP_DATA}/python/bin/python" --reinstall torchvision \
  --index-url https://download.pytorch.org/whl/cpu
mkdir -p "${APP_DATA}/models"
"${APP_DATA}/python/bin/audio-separator" \
  -m UVR_MDXNET_KARA_2.onnx --download_model_only \
  --model_file_dir "${APP_DATA}/models"
