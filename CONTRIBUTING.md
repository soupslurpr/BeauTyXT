# Contributing

Thanks for your interest in contributing!

If you want to suggest a feature or notify us about a bug, please use the issue tracker.

When trying to imolement a feature, please make sure to discuss the planned implementation in the issue for the feature and get approval from @soupslurpr before working on it to ensure it meets the project's requirements.

If you need help with development or have questions it's recommended to join the BeauTyXT room on matrix at
https://matrix.to/#/#beautyxt:matrix.org and ask for help there from [soupslurpr](https://github.com/soupslurpr),
the lead developer.

As of now, translations are not accepted.

Here are some things to know so that your time isn't potentially wasted.
BeauTyXT depends on a Rust library that you must compile or the app will crash on most operations. The source code
for the Rust library can be found at the beautyxt_rs folder. Look at useful-commands.txt for useful commands and
info that will probably help with building.

Java code is not accepted, we will only use Rust and Kotlin. Unsafe Rust code should be avoided, but if there is
truly no other way then it will be heavily scrutinized.

Views should be avoided at all costs and only Jetpack Compose should be used unless there is no other way, but it
has to be very important (unlikely).
