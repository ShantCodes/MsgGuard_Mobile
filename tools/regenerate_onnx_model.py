"""Export the trained SMS classifier to an Android-compatible ONNX model.

The explicit Unicode token expression preserves Persian words. skl2onnx otherwise
replaces scikit-learn's Unicode tokenizer with an ASCII-only expression.
"""

from pathlib import Path

import joblib
import onnx
from skl2onnx import convert_sklearn
from skl2onnx.common.data_types import StringTensorType
from sklearn.feature_extraction.text import CountVectorizer


ROOT = Path(__file__).resolve().parents[1]
MODEL_PATH = ROOT / "sms_model.joblib"
OUTPUT_PATHS = (ROOT / "sms_model.onnx", ROOT / "app/src/main/assets/sms_model.onnx")


def main() -> None:
    classifier = joblib.load(MODEL_PATH)
    model = convert_sklearn(
        classifier,
        initial_types=[("input", StringTensorType([None, 1]))],
        target_opset=22,
        options={
            CountVectorizer: {
                "tokenexp": r"[\p{L}\p{N}_]{2,}",
            }
        },
    )
    onnx.checker.check_model(model)

    for output_path in OUTPUT_PATHS:
        onnx.save_model(model, output_path)
        print(f"Wrote {output_path}")


if __name__ == "__main__":
    main()
