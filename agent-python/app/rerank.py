"""双轨重排：离线 BM25 + 可选 cross-encoder，自动回退，面试可讲 trade-off。"""
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
            score += __import__("math").log(1 + tf) * (1 + __import__("math").log(1 + len(content) / 100.0))
    import math
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

# 可选 cross-encoder：懒加载，缺依赖自动回退
_cross_model = None
_cross_failed = False

def _try_load_cross():
    global _cross_model, _cross_failed
    if _cross_model is not None or _cross_failed:
        return _cross_model
    try:
        from sentence_transformers import CrossEncoder  # type: ignore
        # 轻量中文友好模型，离线无权重时会在首次下载，失败则回退 BM25
        _cross_model = CrossEncoder("cross-encoder/ms-marco-MiniLM-L-6-v2")
        return _cross_model
    except Exception:
        _cross_failed = True
        return None

def rerank(query: str, docs: List[Dict[str, Any]], top_k: int = 4, mode: str = "auto") -> Dict[str, Any]:
    if not docs:
        return {"reranked": [], "mode": "none", "fallback": False}
    use_cross = mode in ("cross-encoder", "auto")
    cross = _try_load_cross() if use_cross else None
    if cross is not None and mode in ("cross-encoder", "auto"):
        try:
            pairs = [(query, d.get("content") or d.get("text") or "") for d in docs]
            scores = cross.predict(pairs)  # type: ignore
            scored = list(zip(docs, scores))
            scored.sort(key=lambda x: float(x[1]), reverse=True)
            top = [d for d, _ in scored[:top_k]]
            return {"reranked": top, "mode": "cross-encoder", "fallback": False, "scores": [float(s) for _, s in scored[:top_k]]}
        except Exception as e:
            # cross 失败，回退 BM25
            if mode == "cross-encoder":
                # 显式要求 cross 时也回退，但标记 fallback
                pass
            else:
                pass
            fallback = True
            cross = None
        else:
            fallback = False
    # BM25 回退
    scored = [(d, _bm25_score(query, d)) for d in docs]
    scored.sort(key=lambda x: x[1], reverse=True)
    top = [d for d, _ in scored[:top_k]]
    return {"reranked": top, "mode": "bm25", "fallback": bool(cross is None and use_cross and _cross_failed), "scores": [float(s) for _, s in scored[:top_k]]}
