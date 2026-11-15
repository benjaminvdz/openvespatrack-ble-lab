# Contributing

Useful contributions include device testing, translations, accessible interface
improvements and reproducible protocol observations.

Describe the problem and expected behaviour, make a focused branch and run
the checks in [TESTING.md](docs/TESTING.md). Include the tested Android version
and remaining hardware limitations in the pull request.

Keep original bytes and metadata. A decoder change must not rewrite an old
recording or completed run. Use labelled synthetic fixtures for automated tests
and screenshots.

Radio starts require an explicit action. Import, recording review, language
changes and process restart must not start BLE. Keep the continuous beacon
separate from bounded simulation and replay profiles.

## Languages and design

English, Dutch and French are release languages. Add matching resource keys,
preserve formatting arguments, and keep schema keys and exported evidence
stable across translations. Follow the AnthroBios Nexus tokens, fonts and
accessible touch sizes in the app resources.

## Report a radio observation

Record phone and receiver models, Android version, app version/commit,
permissions, mode, screen state and battery settings. Describe what an
independent receiver observed. A planned frame, a controller callback and a
received packet are different observations.

Review files before sharing: radio addresses, phone names and notes can identify
people or equipment. Use [the project contact page](https://www.anthrobiosnexus.com/get-in-touch/)
for private reports.

See [SECURITY.md](SECURITY.md) and [PUBLICATION.md](docs/PUBLICATION.md).
