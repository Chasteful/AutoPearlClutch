# AutoClutch Module

## Configuration Options

### Algorithm Settings
- **Algorithm**: Currently only supports `SimulatedAnnealing`
- **MaxIterations**: Maximum calculation iterations (50-10000, default: 5000)
- **IterationsRate**: Iteration multiplier (1-5, default: 5)
- **NoImprovementThreshold**: Stop threshold when no improvements (1000-10000, default: 1337)
- **InitialTemp**: Starting temperature for annealing (5-30, default: 15)
- **MinTemp**: Minimum temperature threshold (0.01-0.1, default: 0.01)
- **CoolingRate**: Temperature reduction rate (0.95-0.99, default: 0.97)

### Aiming Settings
- **AimThreshold**: Rotation accuracy threshold (0.1-1, default: 0.5)
- **PitchLimit**: Pitch angle range (-90 to 45, default: -90 to 0)

### Timing Settings
- **AverageCalcTime**: Calculation time adjustment (0.01-0.15, default: 0.1)
- **Cooldown**: Pearl throw cooldown in ticks (0-20, default: 0)
- **SafetyCheck**: Safety verification duration (5-20 ticks, default: 10)

### Safety Settings
- **VoidLevel**: Y-level considered as void (-256 to 0, default: 0)
- **OnlyDuringCombat**: Only activate during combat (default: false)

## Behavior
- Automatically throws ender pearls when player is falling into void
- Uses simulated annealing algorithm to calculate optimal throw angle
- Includes safety checks to prevent accidental throws
- Compatible with combat situations when configured
- Automatically disables when other movement modules are active
