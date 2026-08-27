
trait FunctionalAst:

    // Stored memory references to the arguments
    def args: Map[String, MemoryRef]

    // Possibly any extra intermediary values created or assigned while executing in a local scope
    def stack: Map[String, Value]

    
    // Then probably each line has its own Ast, it executes mean while mutating the stack
    // Then moving on, and then pushes that all back into the type maybe?
    
