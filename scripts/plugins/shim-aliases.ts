import { viteAliasPlugin } from 'rolldown/experimental'
import type { RolldownPlugin } from 'rolldown'

export default function shimAliases(dir: string): RolldownPlugin {
	return viteAliasPlugin({
		entries: [
			{
				find: 'react/jsx-runtime',
				replacement: `${dir}/react~jsx-runtime.ts`,
			},
			// Do not move React to the top!
			// If you do that, react/jsx-runtime would resolve to ${ShimsDir}/react.ts/jsx-runtime instead.
			{
				find: 'react',
				replacement: `${dir}/react.ts`,
			},
			{
				find: 'react-native',
				replacement: `${dir}/react-native.ts`,
			},
			{
				find: '@react-navigation/native',
				replacement: `${dir}/@react-navigation~native.ts`,
			},
			{
				find: '@react-navigation/stack',
				replacement: `${dir}/@react-navigation~stack.ts`,
			},
			{
				find: '@shopify/flash-list',
				replacement: `${dir}/@shopify~flash-list.ts`,
			},
		],
	})
}
