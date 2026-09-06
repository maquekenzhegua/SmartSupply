"""双轨重排：离线词面打分 + 可选 cross-encoder，自动回退。

诚实命名：_bm25_score 并非标准 BM25（无 IDF、无语料统计），是带长度归一的词面启发式，
与 Java Reranker 保持同构；命名保留 bm25 仅为两侧口径对齐。
cross-encoder 默认 BAAI/bge-reranker-base（中文友好，可经 RERANK_CROSS_MODEL 覆盖）；
权重下载失败自动回退词面打分，回退事实必须体现在返回的 fallback 字段供调用方甄别。
"""
import math
import os
from typing import List, Dict, Any
import re

def _tokenize(text: str) -> List[str]:
    norm = re.sub(r"\s+", " ", (text or "").strip())
    if not norm:
        return []
    tokens: List[str] = []
    for part in re.split(r"[\s，。；：、,.!?；]+", norm):
        part = part.strip()
        if not part:
            continue
        if len(part) <= 6:
            tokens.append(part)
        else:
            for i in range(len(part) - 1):
                tokens.append(part[i:min(i+2, len(part))])
    return tokens

def _bm25_like(query: str, content: str) -> float:
    q_terms = _tokenize(query)
    c_terms = _tokenize(content)
    if not q_terms or not c_terms:
        return 0.0
    from collections import Counter
    freq = Counter(c_terms)
    score = 0.0
    for t in q_terms:
        tf = freq.get(t, 0)
        if tf == 0:
            for ct in c_terms:
                if ct in t or t in ct:
                    tf = 1
                    break
        if tf > 0:
            score += math.log(1 + tf) * (1 + math.log(1 + len(content) / 100.0))
    len_norm = 1.0 / (1 + math.exp((len(c_terms) - 400) / 200.0))
    return score * (0.5 + len_norm)

def _keyword_coverage(query: str, content: str) -> float:
    q_terms = _tokenize(query)
    if not q_terms:
        return 0.0
    lc = (content or "").lower()
    hits = sum(1 for t in q_terms if t.lower() in lc)
    return hits / len(q_terms)

def _bm25_score(query: str, doc: Dict[str, Any]) -> float:
    content = doc.get("content") or doc.get("text") or ""
    title = doc.get("title") or ""
    bm25 = _bm25_like(query, content)
    title_boost = 0 if not title else _bm25_like(query, title) * 0.5
    coverage = _keyword_coverage(query, content)
    return bm25 * 0.6 + coverage * 0.4 + title_boost

# 可选 cross-encoder：懒加载，缺依赖/缺权重自动回退
_cross_model = None
_cross_failed = False

def _try_load_cross():
    global _cross_model, _cross_failed
    if _cross_model is not None or _cross_failed:
        return _cross_model
    try:
        from sentence_transformers import CrossEncoder  # type: ignore
        # 中文友好重排模型；首次使用会下载权重，离线/失败则回退词面打分
        model_name = os.getenv("RERANK_CROSS_MODEL", "BAAI/bge-reranker-base")
        _cross_model = CrossEncoder(model_name)
        return _cross_model
    except Exception:
        _cross_failed = True
        return None

def rerank(query: str, docs: List[Dict[str, Any]], top_k: int = 4, mode: str = "auto") -> Dict[str, Any]:
    if not docs:
        return {"reranked": [], "mode": "none", "fallback": False}
    use_cross = mode in ("cross-encoder", "auto")
    cross = _try_load_cross() if use_cross else None
    if cross is not None and use_cross:
        try:
            pairs = [(query, d.get("content") or d.get("text") or "") for d in docs]
            scores = cross.predict(pairs)  # type: ignore
            scored = list(zip(docs, scores))
            scored.sort(key=lambda x: float(x[1]), reverse=True)
            top = [d for d, _ in scored[:top_k]]
            return {"reranked": top, "mode": "cross-encoder", "fallback": False, "scores": [float(s) for _, s in scored[:top_k]]}
        except Exception:
            # predict 阶段失败同样属于回退：此前未标记，调用方会误以为结果来自 cross-encoder
            global _cross_failed
            _cross_failed = True
            cross = None
    # 词面打分路径（cross 未配置 / 加载失败 / 推理失败）
    fell_back = bool(use_cross and _cross_failed)
    scored = [(d, _bm25_score(query, d)) for d in docs]
    scored.sort(key=lambda x: x[1], reverse=True)
    top = [d for d, _ in scored[:top_k]]
    return {"reranked": top, "mode": "bm25", "fallback": fell_back, "scores": [float(s) for _, s in scored[:top_k]]}
