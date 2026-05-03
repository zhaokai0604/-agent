#!/usr/bin/env python3
"""
随机森林训练脚本（25维特征 - 改进版）。
支持：
1. 从 logs 目录自动构建样本（原有功能）
2. 从标注JSONL文件加载样本（新功能）
3. 改进的特征工程
4. 5折交叉验证
5. 导出 ONNX 模型
"""
from __future__ import annotations

import argparse
import gzip
import json
from pathlib import Path
from typing import List, Tuple, Dict, Any

import numpy as np
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import (
    precision_recall_fscore_support,
    confusion_matrix,
    classification_report
)
from sklearn.model_selection import StratifiedKFold, train_test_split
from skl2onnx import convert_sklearn
from skl2onnx.common.data_types import FloatTensorType


ROOT = Path(__file__).resolve().parents[2]
LOG_DIR = ROOT / "logs"
MODEL_DIR = ROOT / "src" / "main" / "resources" / "models"
MODEL_DIR.mkdir(parents=True, exist_ok=True)
MODEL_PATH = MODEL_DIR / "random-forest-v2.onnx"


def load_from_logs() -> List[Dict[str, Any]]:
    """从 logs 目录加载原始日志并自动标注"""
    samples: List[Dict[str, Any]] = []
    for p in LOG_DIR.glob("**/*.log*"):
        try:
            if p.suffix == ".gz":
                with gzip.open(p, "rt", encoding="utf-8", errors="ignore") as f:
                    lines = [l.strip() for l in f if l.strip()]
            else:
                with p.open("r", encoding="utf-8", errors="ignore") as f:
                    lines = [l.strip() for l in f if l.strip()]
            
            for line in lines:
                label = 1 if any(k in line.lower() for k in 
                                ["error", "fatal", "exception", "timeout", "panic", "crash"]) else 0
                samples.append({
                    "content": line,
                    "label": label,
                    "level": infer_level(line),
                    "template": "",
                    "confidence": 0.0
                })
        except Exception as e:
            print(f"跳过文件 {p}: {e}")
            continue
    return samples


def load_from_jsonl(jsonl_path: Path) -> List[Dict[str, Any]]:
    """从标注 JSONL 文件加载样本"""
    samples: List[Dict[str, Any]] = []
    with jsonl_path.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                sample = json.loads(line)
                samples.append({
                    "content": sample.get("content", ""),
                    "label": int(sample.get("label", 0)),
                    "level": sample.get("level", "INFO"),
                    "template": sample.get("template", ""),
                    "confidence": float(sample.get("confidence", 0.0))
                })
            except Exception as e:
                print(f"跳过无效行: {e}")
                continue
    return samples


def infer_level(line: str) -> str:
    """从日志行推断级别"""
    s = line.lower()
    if "fatal" in s or "critical" in s:
        return "FATAL"
    elif "error" in s:
        return "ERROR"
    elif "warn" in s or "warning" in s:
        return "WARN"
    elif "debug" in s:
        return "DEBUG"
    else:
        return "INFO"


def build_features(samples: List[Dict[str, Any]]) -> Tuple[np.ndarray, np.ndarray]:
    """构建25维特征向量 - 改进版"""
    rows: List[List[float]] = []
    labels: List[int] = []
    
    for sample in samples:
        line = sample["content"]
        label = sample["label"]
        s = line.lower()
        
        level_str = sample.get("level", "INFO").upper()
        
        level = [
            1.0 if "TRACE" in level_str else 0.0,
            1.0 if "DEBUG" in level_str else 0.0,
            1.0 if "INFO" in level_str else 0.0,
            1.0 if "WARN" in level_str else 0.0,
            1.0 if ("ERROR" in level_str or "FATAL" in level_str) else 0.0,
        ]
        
        interval = [0.1, 0.01, 0.1, 0.0]
        
        template_hash = [0.0] * 5
        if line:
            template_hash[hash(line) % 5] = 1.0
        
        keyword_scores = [
            min(1.0, line.lower().count("error") / 3.0),
            min(1.0, line.lower().count("exception") / 3.0),
            min(1.0, line.lower().count("timeout") / 3.0),
        ]
        
        error_rate = [
            0.3 if "error" in s else 0.0,
            0.2 if "exception" in s else 0.0,
            min(1.0, len(line) / 5000.0),
        ]
        
        call_depth = [min(1.0, line.count(" at ") / 30.0)]
        
        entropy = [calculate_entropy(line)]
        
        protocol = [
            1.0 if any(k in s for k in ["mysql", "sql", "jdbc", "database"]) else 0.0,
            1.0 if any(k in s for k in ["http", "nginx", "apache", "status", "request"]) else 0.0,
            1.0 if any(k in s for k in ["kafka", "redis", "zookeeper"]) else 0.0,
        ]
        
        vector = level + interval + template_hash + keyword_scores + error_rate + call_depth + entropy + protocol
        
        if len(vector) < 25:
            vector = vector + [0.0] * (25 - len(vector))
        
        rows.append(vector[:25])
        labels.append(label)
    
    return np.array(rows, dtype=np.float32), np.array(labels, dtype=np.int64)


def calculate_entropy(text: str) -> float:
    """计算字符分布熵"""
    if not text:
        return 0.0
    from collections import Counter
    freq = Counter(text)
    total = len(text)
    entropy = 0.0
    for count in freq.values():
        p = count / total
        entropy -= p * (np.log2(p) if p > 0 else 0)
    return min(1.0, entropy / 7.0)


def main() -> None:
    parser = argparse.ArgumentParser(description="训练随机森林模型")
    parser.add_argument("--training-data", type=str, help="标注数据JSONL文件路径")
    parser.add_argument("--min-samples", type=int, default=3000, 
                        help="最小样本数（不足时扩充）")
    args = parser.parse_args()
    
    print("=" * 60)
    print("随机森林模型训练 - 改进版")
    print("=" * 60)
    
    if args.training_data:
        jsonl_path = Path(args.training_data)
        if not jsonl_path.exists():
            print(f"错误: 文件不存在 {jsonl_path}")
            return
        print(f"从标注文件加载样本: {jsonl_path}")
        samples = load_from_jsonl(jsonl_path)
    else:
        print("从 logs 目录自动构建样本")
        samples = load_from_logs()
    
    print(f"原始样本数: {len(samples)}")
    
    if len(samples) < args.min_samples:
        print(f"样本数不足 {args.min_samples}，通过重复扩充...")
        multiplier = (args.min_samples // max(1, len(samples))) + 1
        samples = (samples * multiplier)[:args.min_samples]
        print(f"扩充后样本数: {len(samples)}")
    
    print("\n构建特征向量...")
    X, y = build_features(samples)
    print(f"特征矩阵形状: {X.shape}")
    print(f"标签分布: 异常={np.sum(y)}, 正常={len(y)-np.sum(y)}")
    
    print("\n初始化随机森林分类器...")
    clf = RandomForestClassifier(
        n_estimators=300,
        max_depth=20,
        min_samples_split=5,
        min_samples_leaf=2,
        random_state=42,
        class_weight="balanced",
        n_jobs=-1
    )
    
    print("\n执行5折交叉验证...")
    skf = StratifiedKFold(n_splits=5, shuffle=True, random_state=42)
    fold_results = []
    
    for fold, (train_idx, test_idx) in enumerate(skf.split(X, y), 1):
        print(f"\n--- 第 {fold} 折 ---")
        X_train, X_test = X[train_idx], X[test_idx]
        y_train, y_test = y[train_idx], y[test_idx]
        
        clf.fit(X_train, y_train)
        y_pred = clf.predict(X_test)
        
        p, r, f, _ = precision_recall_fscore_support(
            y_test, y_pred, average="binary", zero_division=0
        )
        print(f"  Precision: {p:.4f}")
        print(f"  Recall:    {r:.4f}")
        print(f"  F1:        {f:.4f}")
        
        cm = confusion_matrix(y_test, y_pred)
        print(f"  混淆矩阵:\n{cm}")
        
        fold_results.append((p, r, f))
    
    print("\n" + "=" * 60)
    print("交叉验证结果汇总")
    print("=" * 60)
    
    p_list, r_list, f_list = zip(*fold_results)
    report = {
        "precision": float(np.mean(p_list)),
        "recall": float(np.mean(r_list)),
        "f1": float(np.mean(f_list)),
        "precision_std": float(np.std(p_list)),
        "recall_std": float(np.std(r_list)),
        "f1_std": float(np.std(f_list)),
        "sample_size": int(len(X)),
        "feature_size": 25,
        "model_params": {
            "n_estimators": 300,
            "max_depth": 20
        }
    }
    
    print(json.dumps(report, ensure_ascii=False, indent=2))
    
    print("\n在完整数据集上训练最终模型...")
    clf.fit(X, y)
    
    feature_importance = dict(zip([
        "levelTrace", "levelDebug", "levelInfo", "levelWarn", "levelErrorFatal",
        "interval1", "interval2", "interval3", "interval4",
        "templateHash0", "templateHash1", "templateHash2", "templateHash3", "templateHash4",
        "keywordError", "keywordException", "keywordTimeout",
        "errorRate1", "errorRate2", "errorRate3",
        "callDepth", "entropy", "protocolDb", "protocolHttp", "protocolQueue"
    ], clf.feature_importances_))
    
    print("\nTop 10 特征重要性:")
    for name, imp in sorted(feature_importance.items(), 
                             key=lambda x: x[1], reverse=True)[:10]:
        print(f"  {name:20s}: {imp:.4f}")
    
    print("\n导出 ONNX 模型...")
    onnx = convert_sklearn(
        clf,
        initial_types=[("float_input", FloatTensorType([None, 25]))],
        target_opset=13
    )
    MODEL_PATH.write_bytes(onnx.SerializeToString())
    print(f"模型已导出 -> {MODEL_PATH}")
    
    print("\n" + "=" * 60)
    print("训练完成！")
    print("=" * 60)


if __name__ == "__main__":
    main()
