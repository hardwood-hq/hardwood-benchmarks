"""The code a benchmark method runs, within the benchmarks' own sources, for its contender's epoch.

A contender's epoch has to change when what it runs changes, and a benchmark method often
does little more than call a helper (`return Scans.hardwoodFiltered(FILE, threshold);`). This
follows the method into the code it reaches (see reached()): the methods it calls, the fields
and constants it uses with every assignment to them, `@Setup` methods' included, the types
nested in a class it names, and the whole source of any other class it names, each followed in
turn. Setup and checking code a field's assignments call is reached with it, which errs
towards a new epoch.

A class is looked up by its simple name in the referring class's package first, then among
its imports, then in the root package. Comments, whitespace and import lines are left out, so
that only a change to code changes the epoch. Java is read with patterns, not parsed: a
reference the patterns miss leaves the epoch as it would be without it, it does not split one.

Left out on purpose:
- the version adapters under src/compat: which one a run compiles depends on the Hardwood
  version, so they differ between the rows of one series by design;
- the classes that make a benchmark's files (`*Generator`, `*Downloader`) or write its reports
  (`BenchReport`): the fixture's name and size stand for the data, and a report is written
  outside the timed code.

Each function takes `read(path)` and `paths()`, so that the same walk runs over the working
tree or over the tree of any benchmarks commit.
"""
import hashlib
import os
import re

ROOT = "src/main/java/"
ROOT_PACKAGE = "dev.hardwood.benchmarks"
# Classes that make a benchmark's files or write its reports measure nothing: the fixture's
# name and size stand for the data, and a report is written outside the timed code.
NOT_MEASURED = re.compile(r".*(Generator|Downloader)$|BenchReport$")


def working_tree():
    """read and paths over the current directory's sources."""
    def read(path):
        with open(path) as f:
            return f.read()

    def paths():
        out = []
        for d, _, files in os.walk(ROOT):
            out += [os.path.join(d, f) for f in files if f.endswith(".java")]
        return out
    return read, paths


def strip_comments(text):
    """`text` without // and /* */ comments, string and character literals kept."""
    out, i, n = [], 0, len(text)
    while i < n:
        c = text[i]
        if text.startswith('"""', i):
            j = text.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append(text[i:j])
            i = j
        elif c in "\"'":
            j = i + 1
            while j < n and text[j] != c:
                j += 2 if text[j] == "\\" else 1
            out.append(text[i:j + 1])
            i = j + 1
        elif text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j < 0 else j
        elif text.startswith("/*", i):
            j = text.find("*/", i + 2)
            i = n if j < 0 else j + 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def normal(text):
    return " ".join(strip_comments(text).split())


def block_end(text, start):
    """The index just after the block whose `{` is at `start`."""
    depth = 0
    for j in range(start, len(text)):
        depth += {"{": 1, "}": -1}.get(text[j], 0)
        if depth == 0:
            return j + 1
    return len(text)


def methods_named(text, name):
    """The source of every method declared as `name` in `text`, comments removed."""
    out = []
    declaration = re.compile(r"^[ \t]*(?:[\w<>\[\],.? @]+\s)?{}\s*\([^;{{}}]*\)\s*(?:throws\s+[\w.,\s]+)?\{{"
                             .format(re.escape(name)), re.M)
    for m in declaration.finditer(text):
        # A call that happens to start a line has no type before the name.
        head = m.group(0).split(name, 1)[0].strip()
        if not head or head in ("return", "new", "else"):
            continue
        out.append(text[m.start():block_end(text, m.end() - 1)])
    return out


class Sources:
    def __init__(self, read, paths):
        self.read = read
        self.by_name = {}
        for path in paths():
            if path.startswith(ROOT) and path.endswith(".java"):
                package = os.path.dirname(path[len(ROOT):]).replace("/", ".")
                self.by_name.setdefault(os.path.basename(path)[:-len(".java")], {})[package] = path
        self.texts = {}

    def text(self, path):
        """The class's source without comments; import and package lines are kept, for resolve."""
        if path not in self.texts:
            self.texts[path] = strip_comments(self.read(path))
        return self.texts[path]

    def body(self, path):
        """The class's source without comments, import and package lines: its code."""
        return re.sub(r"^\s*(?:import|package)\s[^;]*;", "", self.text(path), flags=re.M)

    def package_of(self, path):
        return os.path.dirname(path[len(ROOT):]).replace("/", ".")

    def resolve(self, name, path):
        """The path of class `name` as the class at `path` refers to it, or None."""
        packages = self.by_name.get(name)
        if not packages:
            return None
        own = self.package_of(path)
        if own in packages:
            return packages[own]
        for package, candidate in packages.items():
            if re.search(r"^import\s+{}\.{};".format(re.escape(package), re.escape(name)), self.text(path), re.M):
                return candidate
        return packages.get(ROOT_PACKAGE)

    def path_of(self, cls):
        """The path of benchmark class `cls`, by simple name, or None."""
        packages = self.by_name.get(cls, {})
        return next(iter(sorted(packages.values())), None)


FIELD = re.compile(r"^[ \t]*(?:(?:private|protected|public|static|final|volatile|transient)\s+)+"
                   r"[\w<>\[\],.? ]+?\s+(\w+)\s*(?:=|;)", re.M)


def fields_of(text):
    return set(FIELD.findall(text))


def field_source(text, name):
    """The declaration of field `name` and every assignment to it, `@Setup` methods' included."""
    parts = [m.group(0) for m in re.finditer(r"^[^;{{}}]*?\b{}\s*(?:=[^;]*)?;".format(re.escape(name)), text, re.M)
             if FIELD.match(m.group(0))]
    for m in re.finditer(r"(?<![\w.])(?:this\.)?{}\s*=(?!=)[^;]*;".format(re.escape(name)), text):
        # `Type name = ...` declares a local of the same name, not an assignment to the field.
        line = text[text.rfind("\n", 0, m.start()) + 1:m.start()]
        if not m.group(0).startswith("this.") and re.search(r"[\w>\]]\s+$", line) and not FIELD.match(line + m.group(0)):
            continue
        parts.append(m.group(0))
    return "\n".join(parts)


def nested_source(text, name):
    """The source of the type `name` declared inside `text`, or None."""
    m = re.search(r"\b(?:class|record|interface|enum)\s+{}\b[^{{;]*\{{".format(re.escape(name)), text)
    return text[m.start():block_end(text, m.end() - 1)] if m else None


def reached(sources, path, method):
    """{name: normalised source} of the code `method` of the class at `path` reaches.

    - A call by name, `m(...)`, `this.m(...)` or `Class.m(...)`: that method of its class,
      every overload, as "Class#m".
    - A field of the class it names: the field's declaration and every assignment to it,
      `@Setup` methods' included, as "Class.field"; a constant `Class.NAME` likewise.
    - A type declared inside a class, named on its own or as `Class.Type`: its source, as
      "Class.Type".
    - Any other mention of a benchmarks class: the class's whole source, as "Class".

    Each piece is followed in turn for what it mentions.
    """
    out = {}
    todo = [("method", path, method)]
    seen = set()

    def mentions(text, where):
        own = sources.body(where)
        own_name = os.path.basename(where)[:-len(".java")]
        own_fields = fields_of(own)
        for target, name in re.findall(r"(?<![.\w])(?:([A-Z]\w*)\.|this\.)?([a-z]\w*)\s*\(", text):
            to = sources.resolve(target, where) if target else where
            if to and (target or methods_named(own, name)):
                todo.append(("method", to, name))
        for target, name in re.findall(r"(?<![.\w])([A-Z]\w*)\.([A-Z]\w*)\b", text):
            to = sources.resolve(target, where)
            if to:
                todo.append(("field" if name.isupper() else "nested", to, name))
            elif nested_source(own, target):
                todo.append(("nested", where, target))
        for name in set(re.findall(r"(?<![.\w])([A-Za-z_]\w*)\b(?!\s*\()", text)):
            if name in own_fields:
                todo.append(("field", where, name))
        for name in set(re.findall(r"(?<![.\w])([A-Z]\w*)\b(?!\.)", text)):
            if name == own_name:
                continue
            if nested_source(own, name):
                todo.append(("nested", where, name))
            else:
                todo.append(("class", where, name))

    while todo:
        kind, where, name = todo.pop()
        if NOT_MEASURED.match(os.path.basename(where)[:-len(".java")]):
            continue
        if kind == "class":
            target = sources.resolve(name, where)
            if (target is None or target == path or ("class", target) in seen
                    or NOT_MEASURED.match(os.path.basename(target)[:-len(".java")])):
                continue
            seen.add(("class", target))
            text = sources.body(target)
            out[os.path.basename(target)[:-len(".java")]] = " ".join(text.split())
            mentions(text, target)
            continue
        if (kind, where, name) in seen or ("class", where) in seen:
            continue
        seen.add((kind, where, name))
        text = sources.body(where)
        cls = os.path.basename(where)[:-len(".java")]
        if kind == "method":
            piece, key = "\n".join(methods_named(text, name)), "{}#{}".format(cls, name)
        elif kind == "field":
            piece, key = field_source(text, name), "{}.{}".format(cls, name)
        else:
            piece, key = nested_source(text, name) or "", "{}.{}".format(cls, name)
        if not piece:
            continue
        out[key] = " ".join(piece.split())
        mentions(piece, where)
    # A class taken whole covers the pieces of it taken before.
    for cls in [k for k in out if "#" not in k and "." not in k]:
        for key in [k for k in out if k.startswith(cls + "#") or k.startswith(cls + ".")]:
            del out[key]
    return out


def code_of(sources, cls, method):
    """{name: digest} of everything but the method itself that it reaches, for its epoch."""
    path = sources.path_of(cls)
    if path is None:
        raise SystemExit("benchcode: no class {}".format(cls))
    found = reached(sources, path, method)
    key = "{}#{}".format(cls, method)
    if key not in found:
        raise SystemExit("benchcode: no method {} in {}".format(method, cls))
    # The epoch holds the benchmark method's own source already; its other overloads, often
    # the code it delegates to, stay.
    others = [b for b in methods_named(sources.body(path), method) if not b.lstrip().startswith("public")]
    if others:
        found[key] = " ".join("\n".join(others).split())
    else:
        del found[key]
    return {name: hashlib.sha256(text.encode()).hexdigest()[:12] for name, text in sorted(found.items())}
