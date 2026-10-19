"""Генерирует постэффекты фильтров камеры BlockPhone.

Каждый фильтр = assets/blockphone/post_effect/filter_<id>.json с параметрами для шейдера
shaders/post/filter.fsh. Те же JSON читает Java (CameraFilter/FilterParams), чтобы применять
фильтры к фото в галерее точно так же, как в видоискателе. Параметры меняются только здесь.

Запуск из корня проекта:  python tools/gen_filters.py
"""
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "fabric", "src", "main", "resources", "assets", "blockphone", "post_effect")

LUMA = [0.299, 0.587, 0.114]

# id: (RowR, RowG, RowB, (saturation, contrast, vignette))
# Row = [kR, kG, kB, offset]: out.r = kR*r + kG*g + kB*b + offset
FILTERS = {
    "bw":      (LUMA + [0], LUMA + [0], LUMA + [0], (1.0, 1.10, 0.15)),
    "sepia":   ([0.393, 0.769, 0.189, 0], [0.349, 0.686, 0.168, 0], [0.272, 0.534, 0.131, 0], (1.0, 1.0, 0.20)),
    "vintage": ([0.75, 0.25, 0.05, 0.04], [0.15, 0.75, 0.08, 0.03], [0.10, 0.20, 0.55, 0.06], (0.85, 0.90, 0.45)),
    "cool":    ([0.90, 0, 0, 0], [0, 1.0, 0, 0.01], [0, 0, 1.12, 0.04], (1.0, 1.02, 0.0)),
    "warm":    ([1.10, 0, 0, 0.03], [0, 1.02, 0, 0.01], [0, 0, 0.85, 0], (1.05, 1.02, 0.0)),
    "noir":    (LUMA + [0], LUMA + [0], LUMA + [0], (1.0, 1.50, 0.55)),
    "vivid":   ([1, 0, 0, 0], [0, 1, 0, 0], [0, 0, 1, 0], (1.5, 1.12, 0.10)),
    "faded":   ([0.90, 0, 0, 0.07], [0, 0.90, 0, 0.07], [0, 0, 0.90, 0.08], (0.70, 0.85, 0.0)),
    "night":   ([0.10, 0.25, 0.05, 0], [0.30, 0.90, 0.20, 0.05], [0.05, 0.20, 0.05, 0], (1.0, 1.20, 0.60)),
}


def effect(r, g, b, params):
    sat, contrast, vignette = params
    return {
        "targets": {"swap": {}},
        "passes": [
            {
                "vertex_shader": "minecraft:core/screenquad",
                "fragment_shader": "blockphone:post/filter",
                "inputs": [{"sampler_name": "In", "target": "minecraft:main"}],
                "output": "swap",
                "uniforms": {
                    "FilterConfig": [
                        {"name": "RowR", "type": "vec4", "value": r},
                        {"name": "RowG", "type": "vec4", "value": g},
                        {"name": "RowB", "type": "vec4", "value": b},
                        {"name": "Params", "type": "vec4", "value": [sat, contrast, vignette, 0.0]},
                    ]
                },
            },
            {
                "vertex_shader": "minecraft:core/screenquad",
                "fragment_shader": "minecraft:post/blit",
                "inputs": [{"sampler_name": "In", "target": "swap"}],
                "uniforms": {"BlitConfig": [{"name": "ColorModulate", "type": "vec4", "value": [1.0, 1.0, 1.0, 1.0]}]},
                "output": "minecraft:main",
            },
        ],
    }


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for name, (r, g, b, params) in FILTERS.items():
        with open(os.path.join(OUT, f"filter_{name}.json"), "w", encoding="utf-8") as f:
            json.dump(effect(r, g, b, params), f, indent=2)
    print("Filters written to", OUT)
