// We will have our operator nodes, assignments, all that stuff up here

trait class Evalulator:


    def evaluate():
        // Traverse the FunctionalAst
        // The actual FunctionalTree will have like an array of in order AstTrees parsed from the parser
        // So we iterate through those, do the evaluation/traversal of the nodes, mutating the arguments internal values, which may more may not trigger a recursive call for a different functional associated with something else but ignore that
        // Nodes will have like variable names in them, so we can then use the args to associate those of course.
        // Also the internal vars like temp vars can be created on the vars tree in the program and reused of course.
    