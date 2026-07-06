# metrics-analyzer
Sample code for code metrics analysis.

This application is a Java Source Project Analyzer built using [Spoon](https://spoon.gforge.inria.fr/). It parses Java source code to compute object-oriented code metrics at the class level and extracts dependency relationships (class-level and package-level couplings).

## Calculated Metrics and Formulas

* **Lines of Code (LOC)**: Total number of lines in a class.
* **Method Count**: Number of methods declared in a class.
* **Field Count**: Number of fields declared in a class.
* **Efferent Coupling (Ce)**: The number of unique non-JDK types the class depends on.
* **Lack of Cohesion of Methods (LCOM)**: Calculates the ratio of disjoint method pairs to total method pairs.
  * *Formula*: `LCOM = disjointPairs / totalPairs` (where disjoint pairs are non-static methods that do not access any common fields).
* **Cyclomatic Complexity (CC)**: The total number of decision points across all methods in a class.
  * *Formula*: `CC = max(1, total decision points)` (decision points include `if`, `else if`, `for`, `while`, `switch`, `catch`, `&&`, `||`, `?:`).
* **Weighted Methods Per Class (WMC)**: The sum of complexities of all methods.
  * *Formula*: `WMC = Σ (decision points of method + 1)` for all methods.
* **Response for Class (RFC)**: The number of unique method calls invoked by all methods within the class.
* **Maintainability Index (MI)**: Indicates the maintainability of the class.
  * *Halstead Volume (V)* = `(total operators + total operands) * log2(distinct operators + distinct operands)`
  * *Raw MI* = `171.0 - 5.2 * ln(max(V, 1.0)) - 0.23 * CC - 16.2 * ln(max(LOC, 1))`
  * *Normalized MI* = `max(0, min(100, (Raw MI * 100.0) / 171.0))`

## How to Build

Ensure you have Java 11 or higher and Maven installed. Run the following command in the project root:

```bash
mvn clean package
```

## How to Run

To start the graphical user interface (JavaFX), run:

```bash
mvn javafx:run
```

Alternatively, to run the CLI analyzer directly:

```bash
mvn exec:java -Dexec.mainClass="net.talaatharb.analyzer.service.JavaSourceProjectAnalyzer" -Dexec.args="<path-to-project>"
```

## Coverage Visualization (File Explorer)

In the **File Explorer** tab, use **Generate Coverage** to run tests and generate coverage reports, then visualize line coverage directly in the editor gutter and line background.

Supported report sources:
- **JaCoCo** (`target/site/jacoco/jacoco.xml` or `target/site/jacoco-aggregate/jacoco.xml`)
- **Cobertura** (`target/site/cobertura/coverage.xml` or `target/coverage.xml`)

## Static Analysis Scanners (File Explorer)

The static analysis selector in **File Explorer** includes additional scanners for project-wide analysis:

- FindBugs-compatible scan (via SpotBugs engine)
- Find Security Bugs (FindSecBugs plugin)
- Infer
- SAST (Semgrep)
- jQAssistant

The File Explorer code area now supports direct editing and saving via **Operations → Save Current File**.

Basic Analyzer findings now include debt-oriented metadata and an initial **Quick Fix** action (for TODO/FIXME markers) from the Problems table context menu.

You can now set a per-analyzer baseline from current scan results (**Operations → Set Baseline From Current Results**) and toggle **Show New Issues Only** to focus on newly introduced debt.

Static scanning now supports explicit scope selection in File Explorer:
- **Project**
- **Module/Folder** (based on the selected tree item)
- **Current File**

You can also run **Fix All Safe Issues (Visible)** to apply safe quick fixes in bulk for the currently visible problem list.

Automated quick fixes are now wired through a unified reducer-style refactoring pipeline (`RefactoringAction` + `ProjectRefactoringReducer` + `RefactoringEngine`), so new refactoring actions can be added without changing File Explorer UI logic.

Refactoring operations in the code editor context menu now include:
- **Rename Symbol in File**
- **Extract Constant** (single or all occurrences)
- **Extract Method from Selection** (AST-backed with Spoon)

Compile error results in File Explorer now support multiline detail expansion and direct copy actions.

SpotBugs findings now include richer issue metadata (category, confidence, fixability, effort, suggested fix, tags) similar to PMD/Checkstyle.

The Metrics tab summary includes a **Project Health Dashboard** with debt categories, trend (vs previous run), and top debt hotspots.

Some scanners require external tools to be installed and available on `PATH` (for example `infer` and `semgrep`).

## Screenshots

### Project Explorer & Static Analysis
![Project Explorer & Static Analysis](images/ProjectExplorer-StaticAnalysis.PNG)

### Metrics
![Metrics](images/Metrics.PNG)

### Coupling
![Coupling](images/Coupling.PNG)

### Git Commits
![Git Commits](images/GitCommits.PNG)
