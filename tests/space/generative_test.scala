// using a valuetype description and invariants create a constructive generator function or constructive grammar from the invaraitns
// This is the execution space of course


// Derive the generator grammar directly from the structural schema of the ValueType (e.g., field types, shapes, tails, and offsets).

// synthesis via constraint inversion (correct-by-construction generation)

// in the first test, testing building an inversion scheme to create the program automatically just from the invariant/s

// in the second test show examples of sampling and rejection and the inversion techniques

// Why Inverting the AST is NecessaryConstructive Target: Invariants are validation functions ($\mathcal{I}: \text{Value} \to \text{Boolean}$). Generators are synthesis functions ($\mathcal{G}: \text{Seed}/\text{Param} \to \text{Value}$).  The Inversion Step: You cannot just run an invariant FST to get data; you must transform the invariant AST into generator assignments before compiling it into the execution tree.  