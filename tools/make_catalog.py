"""Собрать каталог для «Магазина» BlockPhone из папки с приложениями.

Каждая подпапка с app.json — одно приложение. Скрипт упаковывает его в <id>.zip, кладёт иконку и пишет catalog.json
(id, название, версия, автор, описание, разрешения, размер и SHA-256 архива). Результат выкладывается куда угодно по
https — например, в репозиторий GitHub (см. docs/APP_STORE.md).

Запуск:  python tools/make_catalog.py <папка с приложениями> <куда положить каталог> ["Название каталога"]
Пример:  python tools/make_catalog.py docs/examples/scripts docs/examples/store "Примеры BlockPhone"

Описание для магазина берётся из app.json: поле "description" (строка или {"ru_ru": ..., "en_us": ...}).
"""
import hashlib
import json
import os
import shutil
import sys
import zipfile

ALLOWED = {".json", ".lua", ".png", ".obj", ".txt", ".md"}


CATEGORIES = {"games", "tools", "social", "media", "education", "finance", "server", "other"}


def pick(value, lang="ru_ru"):
    if isinstance(value, dict):
        return value.get(lang) or value.get("en_us") or next(iter(value.values()), "")
    return value or ""


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    src, out = sys.argv[1], sys.argv[2]
    title = sys.argv[3] if len(sys.argv) > 3 else "BlockPhone apps"
    os.makedirs(os.path.join(out, "apps"), exist_ok=True)
    os.makedirs(os.path.join(out, "icons"), exist_ok=True)
    apps = []
    for name in sorted(os.listdir(src)):
        folder = os.path.join(src, name)
        manifest = os.path.join(folder, "app.json")
        if not os.path.isfile(manifest):
            continue
        with open(manifest, encoding="utf-8") as f:
            app = json.load(f)
        app_id = app.get("id", name).lower()
        archive = os.path.join(out, "apps", app_id + ".zip")
        with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as z:
            for root, _, files in os.walk(folder):
                for file in sorted(files):
                    if os.path.splitext(file)[1].lower() in ALLOWED:
                        path = os.path.join(root, file)
                        z.write(path, os.path.relpath(path, folder).replace("\\", "/"))
        data = open(archive, "rb").read()
        entry = {
            "id": app_id,
            "name": pick(app.get("name", app_id)),
            "version": app.get("version", "1.0"),
            "author": app.get("author", ""),
            "description": pick(app.get("description", "")),
            "permissions": app.get("permissions", []),
            "download": "apps/" + app_id + ".zip",
            "size": len(data),
            "sha256": hashlib.sha256(data).hexdigest(),
            "category": app.get("category", "other") if app.get("category") in CATEGORIES else "other",
        }
        if app.get("uid"):
            entry["uid"] = str(app["uid"]).lower()
        # Чейнджлог: changelog.txt рядом с app.json (необязательно) — «В последнем обновлении» в магазине.
        notes = os.path.join(folder, "changelog.txt")
        if os.path.isfile(notes):
            entry["changelog"] = open(notes, encoding="utf-8").read().strip()[:1500]
            entry["updated"] = int(os.path.getmtime(notes) * 1000)
        icon = os.path.join(folder, "icon.png")
        if os.path.isfile(icon):
            shutil.copy(icon, os.path.join(out, "icons", app_id + ".png"))
            entry["icon"] = "icons/" + app_id + ".png"
        apps.append(entry)
        print("  +", app_id, entry["version"], len(data), "bytes")
    with open(os.path.join(out, "catalog.json"), "w", encoding="utf-8") as f:
        json.dump({"name": title, "apps": apps}, f, ensure_ascii=False, indent=2)
    print("catalog.json:", len(apps), "apps ->", out)


if __name__ == "__main__":
    main()
