"""Bazel rules for Meridian component graphs.

Public macros:
  - meridian_component:           declare one component (js + html template, optional worker/services)
  - meridian_worker_component:    same, with worker-mode defaults
  - meridian_component_manifest:  emit a JSON manifest covering the transitive component graph

Providers (advanced consumers):
  - MeridianComponentInfo:        per-component metadata
  - MeridianComponentGraphInfo:   transitive graph collected by the aspect
"""

MeridianComponentInfo = provider(
    doc = "Metadata for one Meridian web component.",
    fields = {
        "name": "Component target name.",
        "js": "JavaScript source file.",
        "template": "HTML template source file.",
        "worker": "Optional worker JavaScript source file.",
        "services": "Optional JavaScript service modules used by the component or worker.",
        "worker_mode": "Worker execution mode ('none', 'dedicated', 'shared').",
        "render_mode": "Render contract ('view_model' or 'html_fragment').",
        "deps": "Direct Meridian component dependencies.",
    },
)

MeridianComponentGraphInfo = provider(
    doc = "Transitive component graph entries discovered via aspect traversal.",
    fields = {
        "entries": "List of component metadata dicts.",
    },
)

def _entry_key(entry):
    return entry["name"]

def _dedupe_entries(entries):
    seen = {}
    for entry in entries:
        seen[_entry_key(entry)] = entry
    out = []
    for key in sorted(seen.keys()):
        out.append(seen[key])
    return out

def _json_escape(value):
    return value.replace("\\", "\\\\").replace('"', '\\"')

def _json_array(values):
    if not values:
        return "[]"
    return "[" + ", ".join(['"%s"' % _json_escape(v) for v in values]) + "]"

def _json_nullable(value):
    if value == None:
        return "null"
    return '"%s"' % _json_escape(value)

def _entry_to_json(entry):
    return "{\"name\":\"%s\",\"js\":\"%s\",\"template\":\"%s\",\"worker\":%s,\"services\":%s,\"worker_mode\":\"%s\",\"render_mode\":\"%s\",\"deps\":%s}" % (
        _json_escape(entry["name"]),
        _json_escape(entry["js"]),
        _json_escape(entry["template"]),
        _json_nullable(entry["worker"]),
        _json_array(entry["services"]),
        _json_escape(entry["worker_mode"]),
        _json_escape(entry["render_mode"]),
        _json_array(entry["deps"]),
    )

def _validate_modes(ctx):
    worker_mode = ctx.attr.worker_mode
    render_mode = ctx.attr.render_mode

    if worker_mode not in ["none", "dedicated", "shared"]:
        fail("worker_mode must be one of: none, dedicated, shared")
    if render_mode not in ["view_model", "html_fragment"]:
        fail("render_mode must be one of: view_model, html_fragment")
    if worker_mode != "none" and not ctx.file.worker:
        fail("worker must be set when worker_mode is not 'none'")
    if worker_mode == "none" and ctx.file.worker:
        fail("worker_mode must not be 'none' when worker is set")
    if render_mode == "html_fragment" and worker_mode == "none":
        fail("html_fragment render_mode requires a worker-backed component")

def _meridian_component_impl(ctx):
    _validate_modes(ctx)

    deps = []
    dep_files = []
    for dep in ctx.attr.deps:
        if MeridianComponentInfo in dep:
            deps.append(dep[MeridianComponentInfo].name)
        dep_files.append(dep.files)

    direct_files = [ctx.file.js, ctx.file.template]
    worker = None
    if ctx.file.worker:
        worker = ctx.file.worker
        direct_files.append(worker)

    services = list(ctx.files.services)
    direct_files.extend(services)

    info = MeridianComponentInfo(
        name = ctx.label.name,
        js = ctx.file.js,
        template = ctx.file.template,
        worker = worker,
        services = services,
        worker_mode = ctx.attr.worker_mode,
        render_mode = ctx.attr.render_mode,
        deps = deps,
    )

    return [
        info,
        DefaultInfo(
            files = depset(
                direct = direct_files,
                transitive = dep_files,
            ),
        ),
    ]

meridian_component = rule(
    implementation = _meridian_component_impl,
    doc = "Declares one Meridian component with required js and html template files.",
    attrs = {
        "js": attr.label(
            doc = "Component JavaScript implementation.",
            mandatory = True,
            allow_single_file = [".js"],
        ),
        "template": attr.label(
            doc = "Component HTML template source.",
            mandatory = True,
            allow_single_file = [".html"],
        ),
        "worker": attr.label(
            doc = "Optional dedicated/shared worker entrypoint for this component.",
            allow_single_file = [".js"],
        ),
        "services": attr.label_list(
            doc = "Optional JavaScript service modules used by the component or worker.",
            allow_files = [".js"],
        ),
        "worker_mode": attr.string(
            doc = "Worker execution mode: none, dedicated, or shared.",
            default = "none",
        ),
        "render_mode": attr.string(
            doc = "Render contract: view_model or html_fragment.",
            default = "view_model",
        ),
        "deps": attr.label_list(
            doc = "Direct dependencies on other meridian_component targets.",
            providers = [MeridianComponentInfo],
        ),
    },
)

def _meridian_component_aspect_impl(target, ctx):
    entries = []

    if MeridianComponentInfo in target:
        info = target[MeridianComponentInfo]
        entries.append({
            "name": info.name,
            "js": info.js.short_path,
            "template": info.template.short_path,
            "worker": info.worker.short_path if info.worker else None,
            "services": [service.short_path for service in info.services],
            "worker_mode": info.worker_mode,
            "render_mode": info.render_mode,
            "deps": sorted(info.deps),
        })

    if hasattr(ctx.rule.attr, "deps"):
        for dep in ctx.rule.attr.deps:
            if MeridianComponentGraphInfo in dep:
                entries.extend(dep[MeridianComponentGraphInfo].entries)

    return [MeridianComponentGraphInfo(entries = _dedupe_entries(entries))]

meridian_component_aspect = aspect(
    implementation = _meridian_component_aspect_impl,
    attr_aspects = ["deps"],
    doc = "Collects transitive Meridian component metadata through deps.",
)

def _meridian_component_manifest_impl(ctx):
    output = ctx.actions.declare_file(ctx.label.name + ".json")

    entries = []
    for component in ctx.attr.components:
        if MeridianComponentGraphInfo in component:
            entries.extend(component[MeridianComponentGraphInfo].entries)
        elif MeridianComponentInfo in component:
            info = component[MeridianComponentInfo]
            entries.append({
                "name": info.name,
                "js": info.js.short_path,
                "template": info.template.short_path,
                "worker": info.worker.short_path if info.worker else None,
                "services": [service.short_path for service in info.services],
                "worker_mode": info.worker_mode,
                "render_mode": info.render_mode,
                "deps": sorted(info.deps),
            })

    entries = _dedupe_entries(entries)
    lines = ["["]
    for i, entry in enumerate(entries):
        suffix = "," if i < len(entries) - 1 else ""
        lines.append("  %s%s" % (_entry_to_json(entry), suffix))
    lines.append("]")
    ctx.actions.write(output = output, content = "\n".join(lines) + "\n")

    return [DefaultInfo(files = depset([output]))]

meridian_component_manifest = rule(
    implementation = _meridian_component_manifest_impl,
    doc = "Generates a JSON manifest for a set of Meridian components and their transitive deps.",
    attrs = {
        "components": attr.label_list(
            doc = "Root meridian_component targets to include.",
            providers = [MeridianComponentInfo],
            aspects = [meridian_component_aspect],
            mandatory = True,
        ),
    },
)

def meridian_worker_component(
        name,
        js,
        template,
        worker,
        services = [],
        deps = [],
        worker_mode = "dedicated",
        render_mode = "view_model",
        **kwargs):
    """Declares a worker-backed Meridian component with worker-centric defaults."""

    if worker_mode == "none":
        fail("meridian_worker_component requires worker_mode to be dedicated or shared")

    meridian_component(
        name = name,
        js = js,
        template = template,
        worker = worker,
        services = services,
        worker_mode = worker_mode,
        render_mode = render_mode,
        deps = deps,
        **kwargs
    )
