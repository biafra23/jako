"""Phase 1 — walk the Java tree, extract a dependency graph, topologically sort.

Lightweight regex-based parser per the plan §2.2. Captures:
  - package declaration
  - import statements (concrete + wildcard)
  - top-level type declarations (class / interface / enum / record / @interface)
  - referenced unqualified type names (rough — used to resolve same-package edges)

Cycles are handled by computing strongly-connected components (Tarjan) and emitting
each cycle as a single conversion group, in topological order over the SCC DAG.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import Iterable


# ---------------------------------------------------------------------------
# Regex extraction
# ---------------------------------------------------------------------------

_RE_PACKAGE = re.compile(r"^\s*package\s+([\w.]+)\s*;", re.MULTILINE)
_RE_IMPORT = re.compile(r"^\s*import\s+(?:static\s+)?([\w.]+(?:\.\*)?)\s*;", re.MULTILINE)
_RE_TOP_TYPE = re.compile(
    r"^\s*(?:public\s+|protected\s+|private\s+|abstract\s+|final\s+|sealed\s+|non-sealed\s+|static\s+|strictfp\s+)*"
    r"(class|interface|enum|record|@interface)\s+([A-Z][A-Za-z0-9_]*)",
    re.MULTILINE,
)
_RE_LINE_COMMENT = re.compile(r"//[^\n]*")
_RE_BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.DOTALL)
_RE_STRING = re.compile(r'"(?:\\.|[^"\\])*"')
_RE_CHAR = re.compile(r"'(?:\\.|[^'\\])*'")
_RE_TYPE_REF = re.compile(r"\b([A-Z][A-Za-z0-9_]*)\b")

# Keywords/identifiers we never want to treat as type references.
_TYPE_NOISE = frozenset(
    {
        # Java keywords/literals that start uppercase or look type-ish.
        "TRUE", "FALSE", "NULL",
        # Common annotations that aren't project types.
        "Override", "Deprecated", "SuppressWarnings", "FunctionalInterface",
        "Nullable", "NonNull", "NotNull",
        # Self-references; we strip the file's own top types separately anyway.
    }
)


@dataclass
class JavaUnit:
    """One Java source file in the project."""

    source_path: str          # absolute
    target_path: str          # absolute, in output tree
    package: str
    type_names: list[str]     # top-level types declared in this file
    imports: list[str]        # raw import strings
    referenced_types: list[str]  # unresolved type-name candidates
    category: str             # interface | enum | record | class | annotation
    is_test: bool
    # Filled in during graph build:
    dependencies: list[str] = field(default_factory=list)  # source_paths of deps


# ---------------------------------------------------------------------------
# Per-file extraction
# ---------------------------------------------------------------------------


def _strip_noise(text: str) -> str:
    """Remove comments and string/char literals so type-ref regex doesn't false-match inside them."""
    text = _RE_BLOCK_COMMENT.sub(" ", text)
    text = _RE_LINE_COMMENT.sub(" ", text)
    text = _RE_STRING.sub('""', text)
    text = _RE_CHAR.sub("''", text)
    return text


def _category_for(kind: str, all_kinds: list[str]) -> str:
    if kind == "@interface":
        return "annotation"
    # If the first top-level type is the file's primary kind, use that.
    if all_kinds:
        primary = all_kinds[0]
        if primary == "@interface":
            return "annotation"
        return primary
    return "class"


def parse_java_file(path: Path, source_root: Path, output_root: Path, is_test: bool) -> JavaUnit:
    raw = path.read_text(encoding="utf-8", errors="replace")
    stripped = _strip_noise(raw)

    pkg_match = _RE_PACKAGE.search(stripped)
    package = pkg_match.group(1) if pkg_match else ""

    imports = [m.group(1) for m in _RE_IMPORT.finditer(stripped)]

    top = _RE_TOP_TYPE.findall(stripped)
    kinds = [k for k, _ in top]
    names = [n for _, n in top]

    # Type references — minus our own declarations.
    own = set(names)
    refs = set()
    for m in _RE_TYPE_REF.finditer(stripped):
        n = m.group(1)
        if n in own or n in _TYPE_NOISE:
            continue
        refs.add(n)

    relative = path.relative_to(source_root)
    target_rel = relative.with_suffix(".kt")
    # source roots like .../src/main/java  -> output tree .../src/main/kotlin
    target_parts = list(target_rel.parts)
    target_path = output_root / Path(*target_parts)

    return JavaUnit(
        source_path=str(path),
        target_path=str(target_path),
        package=package,
        type_names=names,
        imports=imports,
        referenced_types=sorted(refs),
        category=_category_for(kinds[0] if kinds else "class", kinds),
        is_test=is_test,
    )


# ---------------------------------------------------------------------------
# Dependency graph
# ---------------------------------------------------------------------------


def _index_units(units: list[JavaUnit]) -> tuple[dict[str, JavaUnit], dict[str, list[JavaUnit]]]:
    """Index by FQN (package.Type -> unit) and by SimpleName -> [units]."""
    by_fqn: dict[str, JavaUnit] = {}
    by_simple: dict[str, list[JavaUnit]] = {}
    for u in units:
        for tn in u.type_names:
            fqn = f"{u.package}.{tn}" if u.package else tn
            by_fqn[fqn] = u
            by_simple.setdefault(tn, []).append(u)
    return by_fqn, by_simple


def _resolve_dependencies(
    unit: JavaUnit,
    units: list[JavaUnit],
    by_fqn: dict[str, JavaUnit],
    by_simple: dict[str, list[JavaUnit]],
) -> list[str]:
    """Resolve a unit's referenced types to other in-project units, returning their source paths."""
    deps: set[str] = set()
    own_paths = {unit.source_path}

    # 1) Concrete imports targeting an in-project type.
    for imp in unit.imports:
        if imp.endswith(".*"):
            continue
        if imp in by_fqn:
            dep = by_fqn[imp]
            if dep.source_path not in own_paths:
                deps.add(dep.source_path)

    # 2) Wildcard imports — for each referenced simple-name, see if any unit in that
    #    package declares it.
    wildcard_pkgs = [imp[:-2] for imp in unit.imports if imp.endswith(".*")]
    same_pkg = unit.package

    for ref in unit.referenced_types:
        # Same-package match.
        candidates = by_simple.get(ref, [])
        for cand in candidates:
            if cand.source_path in own_paths:
                continue
            if cand.package == same_pkg:
                deps.add(cand.source_path)
                continue
            if cand.package in wildcard_pkgs:
                deps.add(cand.source_path)

    # 3) Concrete imports for SimpleName -> FQN mapping; sometimes the import provides
    #    a different package than same-package, already covered by (1). Skip.

    return sorted(deps)


# ---------------------------------------------------------------------------
# Tarjan SCC + topological order over SCC DAG
# ---------------------------------------------------------------------------


def _tarjan_sccs(nodes: list[str], edges: dict[str, list[str]]) -> list[list[str]]:
    """Iterative Tarjan. Returns SCCs in reverse-topological order (sinks first)."""
    index_counter = [0]
    stack: list[str] = []
    on_stack: set[str] = set()
    indices: dict[str, int] = {}
    lowlink: dict[str, int] = {}
    result: list[list[str]] = []

    sys_iter_stack: list[tuple[str, Iterable[str]]] = []

    for start in nodes:
        if start in indices:
            continue
        # Iterative DFS.
        sys_iter_stack.append((start, iter(edges.get(start, []))))
        indices[start] = index_counter[0]
        lowlink[start] = index_counter[0]
        index_counter[0] += 1
        stack.append(start)
        on_stack.add(start)

        while sys_iter_stack:
            v, it = sys_iter_stack[-1]
            try:
                w = next(it)
            except StopIteration:
                sys_iter_stack.pop()
                if sys_iter_stack:
                    parent, _ = sys_iter_stack[-1]
                    lowlink[parent] = min(lowlink[parent], lowlink[v])
                if lowlink[v] == indices[v]:
                    comp: list[str] = []
                    while True:
                        x = stack.pop()
                        on_stack.discard(x)
                        comp.append(x)
                        if x == v:
                            break
                    result.append(comp)
                continue
            if w not in indices:
                indices[w] = index_counter[0]
                lowlink[w] = index_counter[0]
                index_counter[0] += 1
                stack.append(w)
                on_stack.add(w)
                sys_iter_stack.append((w, iter(edges.get(w, []))))
            elif w in on_stack:
                lowlink[v] = min(lowlink[v], indices[w])
    return result


def _topo_sort_sccs(units: list[JavaUnit]) -> list[list[JavaUnit]]:
    """Return SCC groups in dependency order (a group's deps appear in earlier groups).

    Only edges within ``units`` participate in the sort — dependencies pointing
    outside the set are dropped (e.g. test files depending on already-ordered
    main files).
    """
    by_path = {u.source_path: u for u in units}
    edges = {
        u.source_path: [d for d in u.dependencies if d in by_path]
        for u in units
    }
    sccs = _tarjan_sccs(list(by_path.keys()), edges)
    # Tarjan emits SCCs in reverse topo order over the condensation — that is, sinks
    # (no outgoing deps) first, which is exactly what we want: deps before dependents.
    groups: list[list[JavaUnit]] = []
    for comp in sccs:
        # Sort within an SCC by path for determinism.
        groups.append(sorted((by_path[p] for p in comp), key=lambda u: u.source_path))
    return groups


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------


@dataclass
class DiscoveryResult:
    units: list[JavaUnit]
    order: list[list[str]]      # source_paths grouped by SCC, in topo order
    cycles: list[list[str]]     # SCCs of size > 1, source_paths

    def to_json(self) -> dict:
        return {
            "units": [asdict(u) for u in self.units],
            "order": self.order,
            "cycles": self.cycles,
        }


def discover(
    source_root: Path,
    output_root: Path,
    *,
    test_root: Path | None = None,
    separate_tests: bool = True,
) -> DiscoveryResult:
    source_root = source_root.expanduser().resolve()
    output_root = output_root.expanduser().resolve()
    main_files = sorted(source_root.rglob("*.java"))
    main_units = [parse_java_file(p, source_root, output_root, is_test=False) for p in main_files]

    test_units: list[JavaUnit] = []
    if test_root is not None:
        test_root = test_root.expanduser().resolve()
        if test_root.exists():
            test_files = sorted(test_root.rglob("*.java"))
            test_units = [parse_java_file(p, test_root, output_root, is_test=True) for p in test_files]

    if separate_tests:
        # Resolve dependencies for main only against main; tests can depend on main+test.
        _, _ = _index_units(main_units)
        by_fqn_main, by_simple_main = _index_units(main_units)
        for u in main_units:
            u.dependencies = _resolve_dependencies(u, main_units, by_fqn_main, by_simple_main)
        main_groups = _topo_sort_sccs(main_units)

        all_units = main_units + test_units
        if test_units:
            by_fqn_all, by_simple_all = _index_units(all_units)
            for u in test_units:
                u.dependencies = _resolve_dependencies(u, all_units, by_fqn_all, by_simple_all)
            test_groups = _topo_sort_sccs(test_units)
        else:
            test_groups = []

        groups = main_groups + test_groups
    else:
        all_units = main_units + test_units
        by_fqn, by_simple = _index_units(all_units)
        for u in all_units:
            u.dependencies = _resolve_dependencies(u, all_units, by_fqn, by_simple)
        groups = _topo_sort_sccs(all_units)

    order = [[u.source_path for u in g] for g in groups]
    cycles = [grp for grp in order if len(grp) > 1]

    flat_units: list[JavaUnit] = []
    for grp in groups:
        flat_units.extend(grp)

    return DiscoveryResult(units=flat_units, order=order, cycles=cycles)


def write_discovery(result: DiscoveryResult, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(result.to_json(), indent=2))
