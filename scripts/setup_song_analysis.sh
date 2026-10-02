#!/usr/bin/env bash
set -euo pipefail

# Optional local research runtime. Stems and model weights stay outside the repository.
APP_DATA="${HOME}/.local/share/intonation-trainer"
ACCELERATOR="${INTONATION_ACCELERATOR:-auto}"
if [[ "${ACCELERATOR}" == auto ]]; then
  if command -v nvidia-smi >/dev/null && nvidia-smi -L >/dev/null 2>&1; then
    ACCELERATOR=cuda
  else
    ACCELERATOR=cpu
  fi
fi
if [[ "${ACCELERATOR}" != cpu && "${ACCELERATOR}" != cuda ]]; then
  echo "INTONATION_ACCELERATOR must be auto, cpu or cuda" >&2
  exit 2
fi
if [[ "${ACCELERATOR}" == cuda ]] && { ! command -v nvidia-smi >/dev/null || ! nvidia-smi -L >/dev/null 2>&1; }; then
  echo "CUDA requested, but an NVIDIA driver was not found" >&2
  exit 2
fi
uv venv --python python3.10 "${APP_DATA}/python"
uv pip install --python "${APP_DATA}/python/bin/python" \
  'audio-separator==0.47.0'
if [[ "${ACCELERATOR}" == cuda ]]; then
  uv pip uninstall --python "${APP_DATA}/python/bin/python" onnxruntime onnxruntime-gpu || true
  uv pip install --python "${APP_DATA}/python/bin/python" --reinstall torch torchvision \
    --index-url https://download.pytorch.org/whl/cu128
  uv pip install --python "${APP_DATA}/python/bin/python" 'onnxruntime-gpu==1.23.2'
  "${APP_DATA}/python/bin/python" - <<'PY'
import onnxruntime as ort
import torch
if not torch.cuda.is_available() or "CUDAExecutionProvider" not in ort.get_available_providers():
    raise SystemExit("CUDA libraries installed, but PyTorch or ONNX Runtime cannot use the GPU")
print("GPU acceleration is available:", torch.cuda.get_device_name(0))
PY
else
  uv pip uninstall --python "${APP_DATA}/python/bin/python" onnxruntime-gpu || true
  uv pip install --python "${APP_DATA}/python/bin/python" --reinstall torch torchvision \
    --index-url https://download.pytorch.org/whl/cpu
  uv pip install --python "${APP_DATA}/python/bin/python" 'onnxruntime==1.23.2'
  echo "No supported GPU backend selected; separation will use the CPU"
fi
mkdir -p "${APP_DATA}/models"
"${APP_DATA}/python/bin/audio-separator" \
  -m UVR-MDX-NET_Main_406.onnx --download_model_only \
  --model_file_dir "${APP_DATA}/models"
"${APP_DATA}/python/bin/audio-separator" \
  -m htdemucs_ft.yaml --download_model_only \
  --model_file_dir "${APP_DATA}/models"
"${APP_DATA}/python/bin/audio-separator" \
  -m vocals_mel_band_roformer.ckpt --download_model_only \
  --model_file_dir "${APP_DATA}/models"

# Basic Pitch needs NumPy 1.x while current Audio Separator uses NumPy 2.x.
# Keep the two model stacks isolated so installation is reproducible.
uv venv --python python3.10 "${APP_DATA}/ml-venv"
uv pip install --python "${APP_DATA}/ml-venv/bin/python" \
  'basic-pitch==0.4.0' 'soundfile>=0.12,<1' 'numpy<2'
