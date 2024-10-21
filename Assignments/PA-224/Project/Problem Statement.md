# Problem Statement

2024233125 Chen Xuanxin 🤖

Based on ICSE2024 paper and codes: [Fuzz4All](https://arxiv.org/abs/2308.04748)

## Background

### What is fuzzing?

It is into generating random inputs of program for testing. In specific field, it can include requirement analysis and fuzzing. 

Firstly, based on the provided instructions—such as input descriptions, how to run the target program, program structure, etc.—and the environment details, including the target system, target lang. Second, follow the precondition to begin fuzzing. 

### Why choose LLMs as the assistant?

Large Language Models (LLMs) are pre-trained on vast amounts of data across various programming and formal languages, which gives them an intuitive understanding of the syntax and semantics of these languages.

It is utilized in two process to assist fuzzing work. To be specific, LLMs can handle some necessary contents  in natural language to other formats, like Signal Temporal Logic (STL), pseudocode, data structure and other program languages. Additionally, they can improve the diversity of contents, for example, they can engage in mutation and generation tasks and effectively return distinct results.

However, traditional fuzzing methods usually apply random feeds to achieve the aims and generation approaches use defined grammar and concrete code snippets connection structure. Anyway, they can play a role in text interaction processing and analysis to some extent.

## Challenge

### Reliable on Target Properties

Due to the limitations of fuzzing tasks, the generated examples are constrained by the target testing systems and language. Without properly formatted examples, fuzzing tasks cannot be completed successfully. 

As a result, most `fuzzers` specialize in a single programming language and design a corresponding framework to operate within. This approach reduces the generality of traditional `fuzzers`, as they are often not reusable in different scenarios or environments.

### Mutation Gap

Another challenge of conventional `fuzzers` lies in mutation. The goal of mutation is to generate as many diverse input examples as possible. However, in typical frameworks, the generated examples often follow a rigid, single direction rather than the logical mutations that are expected. 

This limitation stems from the outdated mutation techniques used by traditional methods, which rely on the combination of program segments rather than natural language processing or logical inference.

### Generation Limit

A major flaw of traditional `fuzzers` in generating examples is their reliance on fixed dataset grammars, which restricts the flexibility of inputs. This constraint prevents the generation of diverse and logically inferred examples, limiting the `fuzzer's` effectiveness in exploring a broader range of test cases.

## Solution

With the assistance of LLMs, two optimization directions are given:  `adaptive strategy` and `diversity enhancement`.

### Adaptive Strategy

By leveraging LLMs, `fuzzers` can dynamically adapt to different programming languages and environments by understanding the underlying syntax and semantics. This enables `fuzzers` to generate logically relevant test cases that suit the specific target, overcoming the rigidity of traditional approaches.

### Diversity Enhancement

LLMs can improve the diversity of input examples by utilizing natural language processing and logical inference, creating more varied and unpredictable mutations. This allows `fuzzers` to explore a wider range of inputs, enhancing the chances of uncovering hidden vulnerabilities in the target program.