# Contributing

Thanks for your interest in contributing!

If you want to suggest a feature or notify us about a bug, please use the issue tracker.

If you need help with development or have questions its recommended to join the BeauTyXT room on matrix at
https://matrix.to/#/#beautyxt:matrix.org and ask for help there from [soupslurpr](https://github.com/soupslurpr),
the lead developer.

Here are some things to know so that your time isn't potentially wasted.
BeauTyXT depends on a Rust library that you must compile or the app will crash on most operations. The source code
for the Rust library can be found at the beautyxt_rs folder. Look at useful-commands.txt for useful commands and
info that will probably help with building.

Java code is not accepted, we will only use Rust and Kotlin. Unsafe Rust code should be avoided, but if there is
truly no other way then it will be heavily scrutinized.

Views should be avoided at all costs and only Jetpack Compose should be used unless there is no other way, but it
has to be very important (unlikely).