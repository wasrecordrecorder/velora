<div align="center">

# Velora

**A purpose-built scripting language and bytecode VM for Minecraft clients.**

Fast to embed. Strict where it matters. Designed around events, scripts, settings, async execution and real-time client workloads.

[![Release](https://img.shields.io/github/v/release/wasrecordrecorder/velora?style=flat-square\&label=release)](https://github.com/wasrecordrecorder/velora/releases/latest)
![Java](https://img.shields.io/badge/Java-21-ED8B00?style=flat-square)
![Runtime](https://img.shields.io/badge/runtime-custom%20VM-6E56CF?style=flat-square)
![Language](https://img.shields.io/badge/language-Velora-111827?style=flat-square)

</div>

---

Velora is an embeddable scripting language and runtime designed primarily for **Minecraft client development**.

It is not a Lua wrapper and it does not embed JavaScript.

Velora owns the complete execution pipeline:

```text
.vls source
    │
    ▼
  Lexer
    │
    ▼
  Parser
    │
    ▼
   AST
    │
    ▼
Semantic Analysis
    │
    ▼
    IR
    │
    ▼
 Optimizer
    │
    ▼
 Bytecode
    │
    ▼
 Verifier
    │
    ▼
 Velora VM
    │
    ├── Fibers
    ├── Tasks
    ├── Events
    └── Host API
```

The core runtime is host-agnostic, while its API model is built around the requirements of real-time applications such as Minecraft clients.

## Language at a glance

```vls
@Script("Counter")
@Version("1.0.0")
@Author("was_record")
@Description("A minimal Velora script")

script Counter {

    @Setting(
        "Step",
        min = 1,
        max = 10,
        step = 1,
        description = "Amount added on every run"
    )
    Int step = 1

    @Persistent
    Int total = 0

    Int add(Int value) {
        total += value
        return total
    }

    @Run
    run() {
        total = add(step)
    }
}
```

Velora provides typed declarations without forcing unnecessary verbosity. Types can also be inferred where possible.

```vls
count = 10
name = "Velora"

Int explicit = 32
String? nullable = null

List<String> names = ["Steve", "Alex"]
```

Control flow is intentionally familiar:

```vls
if (health <= 6) {
    state = "critical"
} else {
    state = "stable"
}

for (player in players) {
    process(player)
}

when (state) {
    "critical" -> {
        react()
    }

    "stable", "safe" -> {
        continueWork()
    }

    else -> {
        reset()
    }
}
```

The language also supports:

```text
if / else
while
for / in
when
return
async
spawn
await

nullable types
safe member access ?.
Elvis operator ?:
named arguments
default arguments
string interpolation
generic collections
duration literals
increment / decrement
compound assignments
type checks with is
```

## Built for scripting

Velora treats scripts as first-class runtime objects rather than isolated pieces of evaluated code.

Every script can participate in a managed lifecycle:

```vls
@Load
load() {
}

@Enable
enable() {
}

@Run
run() {
}

@Disable
disable() {
}

@Unload
unload() {
}
```

Host integrations can expose additional events as annotations.

For example, a Minecraft integration may register an event such as:

```vls
@Tick
tick() {
}
```

Event names and payloads are provided by the host rather than hardcoded into the Velora core.

## Settings

Configuration is part of the language.

```vls
@Setting(
    "Range",
    min = 1.0,
    max = 6.0,
    step = 0.1,
    category = "Combat",
    description = "Maximum interaction range"
)
Double range = 3.0
```

Settings support metadata and constraints including:

* `min`
* `max`
* `step`
* `minLength`
* `maxLength`
* `values`
* `pattern`
* `editor`
* `kind`
* `description`
* `category`
* `order`
* `advanced`
* `restartRequired`
* `secret`
* `idAlias`

Hosts may also register completely custom setting kinds and editors.

## Persistent state

Fields can survive script reloads through persistent state:

```vls
@Persistent
Int launches = 0
```

A custom persistence identifier can be supplied when required:

```vls
@Persistent("runtime.launches")
Int launches = 0
```

## Async execution

Velora contains its own cooperative scheduler.

Scripts are executed through lightweight fibers instead of allowing arbitrary script work to block the host thread.

The runtime provides:

* asynchronous functions
* fibers
* `spawn`
* `await`
* task completion
* cancellation
* sleep scheduling
* per-script scheduling
* main-thread aware API calls
* worker execution
* execution budgets

This model is designed for environments where script execution shares a frame or tick loop with the application.

## Resource isolation

A script should not be able to accidentally consume an unlimited amount of runtime resources.

Velora exposes configurable limits for:

```text
instructions / fiber / tick
instructions / script / tick
instructions / engine / tick
API cost / script / tick
memory / script
fibers / script
tasks / script
event queue size
call depth
string length
collection size
collection depth
engine wall time / tick
```

Example:

```java
VeloraLimits limits = VeloraLimits.builder()
        .instructionsPerFiberTick(5_000)
        .instructionsPerScriptTick(30_000)
        .maxFibersPerScript(64)
        .memoryPerScript(32L * 1024L * 1024L)
        .build();
```

## Embedding

Velora is designed to be embedded into another Java application.

Create a host implementation:

```java
public final class ClientHost implements VeloraHost {

    @Override
    public String id() {
        return "my-client";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public MainThreadExecutor mainThread() {
        return mainThread;
    }

    @Override
    public WorkerExecutor workers() {
        return workers;
    }

    @Override
    public VeloraClock clock() {
        return clock;
    }

    @Override
    public VeloraLogger logger() {
        return logger;
    }

    @Override
    public VeloraFileSystem fileSystem() {
        return fileSystem;
    }
}
```

Then create the engine:

```java
VeloraEngine engine = Velora.builder()
        .host(host)
        .limits(VeloraLimits.defaults())
        .build();
```

Register the host API before freezing the engine:

```java
engine.api().registerAnnotated(new PlayerApi());
engine.api().registerAnnotated(new ClientApi());

engine.extensions().register(new MinecraftExtension());

engine.freeze();
```

Discover and load scripts:

```java
engine.scripts().discover();
engine.scripts().loadEnabled();
```

Advance Velora from the application's main tick:

```java
engine.tick();
```

And close it with the host:

```java
engine.close();
```

## Java bindings

Java APIs can be exposed to Velora through annotations.

```java
@VeloraNamespace("player")
public final class PlayerApi {

    @VeloraProperty(
            name = "health",
            returnType = "Double",
            description = "Current player health"
    )
    public double health() {
        return getPlayerHealth();
    }

    @VeloraFunction(
            name = "message",
            description = "Sends a client-side message"
    )
    public void message(
            @VeloraParam("text") String text
    ) {
        sendMessage(text);
    }
}
```

The exposed API becomes part of the language environment without coupling the Velora compiler to Minecraft or another specific host.

Bindings can define:

* namespaces
* functions
* properties
* parameter names
* Velora types
* execution thread requirements
* suspending functions
* execution cost
* categories
* descriptions

## Extensions

Larger integrations can be packaged as `VeloraExtension`s.

```java
public final class MinecraftExtension implements VeloraExtension {

    @Override
    public String id() {
        return "minecraft";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public void register(VeloraExtensionContext context) {
        // Register types, events, functions,
        // settings, constants and templates.
    }
}
```

An extension receives access to:

```text
API registry
event registry
type registry
setting registry
constant registry
script templates
categories
```

This keeps integrations modular while the core runtime remains independent.

## Event system

Events are registered by the host and become script annotations.

```java
EventDescriptor event = EventDescriptor.builder("minecraft.tick")
        .scriptName("Tick")
        .payloadType(VeloraTypes.UNIT)
        .queueLimit(256)
        .build();

engine.events().register(event);
```

Scripts can then react naturally:

```vls
@Tick
tick() {
}
```

Velora events support configurable concurrency and overflow behavior, including queue limits and coalescing strategies.

## Type system

Built-in types include:

```text
Unit
Nothing

Boolean
Byte
Int
Long
Float
Double
Char

String
Duration
UUID

Vec2
Vec3
Color

List<T>
Set<T>
Map<K, V>
Task<T>
```

Hosts may register additional structured, enum and handle types.

## Compiler

Compilation is separated into explicit stages instead of directly interpreting source text.

```text
Source
  ↓
Lexer
  ↓
Parser
  ↓
Semantic Analyzer
  ↓
IR Builder
  ↓
IR Verifier
  ↓
IR Optimizer
  ↓
Bytecode Writer
  ↓
Bytecode Verifier
  ↓
Compiled Module
```

Velora also includes bytecode caching and source revision tracking to avoid unnecessary work when scripts have not changed.

## Virtual machine

Velora bytecode executes inside a dedicated stack-based VM.

The runtime contains its own:

* instruction set
* constant pool
* compiled modules
* compiled functions
* bytecode verifier
* call frames
* value stack
* script values
* scheduler
* resource accounting
* runtime error handling

This makes script execution independent from JVM bytecode generation and gives the host control over scheduling and runtime limits.

## Script management

The public script API supports:

```text
create
delete
enable
disable
toggle
reload
unload

discover
load enabled scripts

inspect diagnostics
inspect settings
edit settings
transactions
revision management
```

Scripts transition through explicit runtime states instead of being treated as loose files.

## Language tooling

Velora includes a language-service layer intended for IDEs and embedded script editors.

Editor sessions support:

* diagnostics
* completions
* hover information
* signature help
* go to definition
* formatting
* rename
* syntax tokens

```java
try (EditorSession editor =
         engine.language().openEditor("combat", "combat.vls")) {

    editor.updateText(source);

    EditorSnapshot snapshot = editor.snapshot();

    var completions = editor.completions(line, column);
    var hover = editor.hover(line, column);
    var signature = editor.signatureHelp(line, column);
}
```

This allows Minecraft clients to provide their own integrated Velora editor without implementing language intelligence from scratch.

## Debugging and profiling

The debug service exposes runtime information per script:

```text
logs
runtime errors
profiler data
fibers
tasks
complete debug snapshots
```

```java
DebugSnapshot snapshot = engine.debug().snapshot(scriptId);
```

Individual fibers can also be terminated through the debugging API.

## Core architecture

```text
io.velora
│
├── api
│   ├── compiler
│   ├── debug
│   ├── event
│   ├── function
│   ├── language
│   ├── registry
│   ├── script
│   ├── setting
│   ├── task
│   └── type
│
├── binding
│
├── host
│
└── internal
    ├── ast
    ├── bytecode
    ├── compiler
    ├── debug
    ├── event
    ├── ir
    ├── language
    ├── lexer
    ├── parser
    ├── persistence
    ├── registry
    ├── runtime
    ├── scheduler
    ├── script
    ├── security
    ├── semantic
    ├── setting
    └── vm
```

Public integrations should use `io.velora.api`, `io.velora.host` and the binding API rather than depending on `io.velora.internal`.

## Installation

Velora requires **Java 21 or newer**.

Download the latest build from:

**[GitHub Releases](https://github.com/wasrecordrecorder/velora/releases/latest)**

Release artifacts:

```text
velora-1.0.0.jar
velora-1.0.0-sources.jar
velora-1.0.0-javadoc.jar
```

Add the runtime JAR to your project and create a `VeloraHost` implementation for your client.

## File extension

Velora scripts use:

```text
.vls
```

Example:

```text
scripts/
├── combat.vls
├── movement.vls
├── render.vls
└── utilities.vls
```

## Design goals

Velora is built around several principles:

**Predictable execution**
Script work is scheduled and budgeted instead of running without control.

**Strong host boundaries**
Minecraft-specific behavior belongs to an extension or host API, not the language core.

**Low integration friction**
Java APIs can be exposed declaratively and scripts can be managed through one engine facade.

**Tooling from the start**
Compiler diagnostics and editor services are part of the architecture rather than an afterthought.

**Runtime control**
The host controls resources, threading, events, persistence and script lifecycle.

**A language built for the problem**
Velora does not need to inherit the runtime model of a general-purpose embedded language.

## Current release

### Velora 1.0.0

The initial stable release establishes the core language, compiler, bytecode format, virtual machine, scheduler and public embedding API.

See the complete release on the **[Releases](https://github.com/wasrecordrecorder/velora/releases)** page.

---

<div align="center">

**Velora**

*Script the client. Control the runtime.*

</div>
