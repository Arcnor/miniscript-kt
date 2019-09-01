# MiniScript-kt

This is a kotlin version of the excellent MiniScript language by Joe Strout (http://miniscript.org), made by Edu García.

The code is a 1:1 port from the C# version, which means the code is far from idiomatic (for example, method names are capitalized as in C#). Also, I'm 99% sure there are subtle bugs still somewhere and more testing is required.

An effort has been made to use only common Kotlin code to be able to compile for all platforms Kotlin supports. However, only the JVM target is working right now, and the JS/Native ports have stubs for almost all required functions.

Optimizations are certainly possible and performance hasn't been measured, nor it was the goal of this implementation.

## Known Bugs
* Number formatting is not 100% accurate when dealing with extreme values (numbers with more than 6 decimal places or past certain big numbers. Also, the exponential output doesn't match the C# version 100%).

  A "simple" fix could be using the `DecimalFormat` JVM class, but I'm not sure about the performance implications of this (although performance is not a goal, there is no reason to reduce it "just because" :D).

* The `version` intrinsic doesn't work