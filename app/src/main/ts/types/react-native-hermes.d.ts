declare global {
	function nativeLoggingHook(message: string, level: number): void

	var __DEV__: boolean

	// Fabric's JSI binding
	var nativeFabricUIManager: any
}

export {}
