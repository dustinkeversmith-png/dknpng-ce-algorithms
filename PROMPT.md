You are a principal software architect and technical project manager. 
    A developer has gone through a codebase and left explicit comments, TODOs, and architectural notes detailing what needs to be refactored or decoupled.
    
    Here is a list where the most important refactoring comments have been left.


    ProblemContext.scala updating
    DemoRunner.scala refactoring
    StrategyKind.scala
    StrategicInterpreter.scala
    Need to then also update the registries of course and add the new capabilities to the operations
    StrategyOperation.scala
    BasicSearchOperation.scala
    JsonContextValueCodec.scala
    ProblemSpace.scala
    ChoiceExecutor.scala

    I have provided this refactoring plan and information to help aid in this but make sure that the refactoring specifications are done well.
    
    Provide your response in clean Markdown with the following layout:
    
    ## 1. High-Priority Structural Bottlenecks
    Identify which files contain the most critical architectural directives (e.g., decoupling core systems, fixing global state, breaking up massive classes). Highlight the specific comments that must be addressed first.
    
    ## 2. Order of Execution (Batched Dependency Roadmap)
    Organize the refactoring process into chronological batches. 
    Group the files together logically (e.g., Batch 1: Independent components/utilities mentioned in comments, Batch 2: Intermediate architectural re-wiring, Batch 3: High-level system integration). 
    Explain why fixing the comments in Batch 1 makes it safer to address the comments in Batch 2.
    
    ## 3. Targeted Prompt Prompts for the Execution Phase
    For each batch, write a precise system prompt we can pass to an execution LLM later. This prompt should instruct the LLM exactly how to parse the code file, find those specific line-number comments, execute the requested change, and scrub/remove the "TODO" comment once the fix is complete.


    Return a zipped file with the new implementation with the refactors and better structure.