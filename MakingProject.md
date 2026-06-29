## Step 1: Install the Metals Extension

Before doing anything in your project folder, you need to give VS Code Scala superpowers.

1. Open VS Code.

2. Click on the **Extensions** icon on the left sidebar (or press `Ctrl+Shift+X`).

3. Search for **Scala (Metals)** and click **Install**.

4. *(Optional but highly recommended)* Search for and install **Scala Syntax (official)** if it didn't install automatically.

## Step 2: Create a Project Folder

1. Open your terminal (Command Prompt or PowerShell).

2. Create a new directory for your project and move into it:
   
   PowerShell
   
   ```
   mkdir my-scala-project
   cd my-scala-project
   ```

3. Open this folder in VS Code:
   
   PowerShell
   
   ```
   code .
   ```

## Step 3: Create Your First Scala File

Inside VS Code, use the file explorer on the left to create a new file named **`Hello.scala`**.

Paste the following minimal Scala 3 code into it:

Scala

```
@main def hello(): Unit =
  println("Hello, VS Code and Scala!")
```

## Step 4: Import the Build (The Magic Step)

The moment you save your `.scala` file, a small pop-up notification will appear in the bottom right corner of VS Code from the Metals extension asking:

> *"New Scala CLI workspace detected. Would you like to import the build?"*

Click **Import build**.

Metals will take a few seconds to connect to Scala CLI and configure your workspace. You'll see a tiny spinning status indicator at the bottom of the window.

## Step 5: Run Your Project

Once Metals finishes importing, look closely at your code inside `Hello.scala`. You will see small interactive text buttons appear directly above your code (these are called Code Lenses):

1. Click the **run** button that appears right above `@main def hello()`.

2. Alternatively, you can open the VS Code terminal (`Ctrl+~`) and type:
   
   PowerShell
   
   ```
   scala-cli run .
   ```

You will see `Hello, VS Code and Scala!` print out in the terminal panel.

## Adding Libraries Later (Bonus)

If you ever need to add external dependencies (like an HTTP client or a JSON parser), you don't need an external configuration file. You can add them as comments directly at the top of your `Hello.scala` file using Scala CLI directives:

Scala

```
//> using dep com.lihaoyi::os-lib:0.11.3

@main def hello(): Unit =
  println(os.pwd) // Uses the library instantly!
```

Whenever you add a `//> using dep`, Metals will prompt you to import the build again, automatically fetching the library for you.
