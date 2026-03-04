declare global {
	function nativeLoggingHook(message: string, level: number): void

	var __DEV__: boolean
}

export {}
