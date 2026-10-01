declare global {
	// Installed by Covalent's native code, see app/src/main/cpp/inspector.cpp
	var __covalentElementInspectorHost:
		| {
				setInspecting(inspecting: boolean): void
				highlight(rects: number[] | null): void
		  }
		| undefined

	// Installed by Covalent's native code, see app/src/main/cpp/devtools.cpp
	var __covalentDevToolsConsole:
		| ((type: number, ...args: unknown[]) => void)
		| undefined

	// Called by Covalent's native code, see react-devtools-inspector.ts
	var __covalentElementInspector: {
		inspectAt(x: number, y: number, done: boolean): void
	}
}

export {}
