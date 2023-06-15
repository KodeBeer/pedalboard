Engine_Pedalboard : CroneEngine {
  var allPedalDefinitions;
  var allPedalIds;
  var boardIds;
  var optionalPedalLookup;
  var pedalSetupState;
  var pedalDetails;
  var buses;
  var passThru;
  var inputStage;
  var inputAmp = 1;
  var outputStage;
  var outputAmp = 1;
  var numInputChannels = 2;

  *new { arg context, doneCallback;
    ^super.new(context, doneCallback);
  }

  alloc {
    // Set up pedal definitions and commands
    allPedalDefinitions = [
      AmpSimulatorPedal,
      AutoWahPedal,
      BitcrusherPedal,
      ChorusPedal,
      CloudsPedal,
      CompressorPedal,
      DelayPedal,
      DistortionPedal,
      EqualizerPedal,
      FlangerPedal,
      LoFiPedal,
      OverdrivePedal,
      PhaserPedal,
      PitchShifterPedal,
      PitchShifterPedal2,
      PitchShifterPedal3,
      PitchShifterPedal4,
      ReverbPedal,
      RingModulatorPedal,
      RingsPedal,
      SubBoostPedal,
      SustainPedal,
      TremoloPedal,
      TunerPedal,
      VibratoPedal,
      WavefolderPedal,
    ];
    allPedalIds = List.new;
    optionalPedalLookup = Dictionary.new;
    pedalSetupState = Dictionary.new;
    pedalDetails = Dictionary.new;
    allPedalDefinitions.do({|pedalDefinition|
      if (pedalDefinition.addOnBoot, {
        pedalDefinition.addDef(context);
        pedalSetupState[pedalDefinition.id] = 1;
      }, {
        optionalPedalLookup[pedalDefinition.id] = pedalDefinition;
        pedalSetupState[pedalDefinition.id] = 2;
      });
      allPedalIds.add(pedalDefinition.id);
      pedalDetails[pedalDefinition.id] = Dictionary.new;
      pedalDetails[pedalDefinition.id][\arguments] = Dictionary.new;
      pedalDefinition.arguments.do({|argument|
        this.addCommand(pedalDefinition.id ++ "_" ++ argument, "f", {|msg|
          pedalDetails[pedalDefinition.id][\arguments].add(argument -> msg[1]);
          if (pedalDetails[pedalDefinition.id][\synth].notNil, {
            pedalDetails[pedalDefinition.id][\synth].set(argument, msg[1]);
          });
        });
      });
      this.addPoll(pedalDefinition.id ++ "_ready_poll", {
        pedalSetupState[pedalDefinition.id];
      });
    });

    // Make a simple passthru synth for the empty board
    SynthDef(\passThru, {|inL, inR, out, amp=1|
      Out.ar(out, [In.ar(inL), In.ar(inR)] * amp);
    }).add;
    context.server.sync;

    boardIds = List[];
    buses = List.new;
    // Start the buses with a bus coming from inputStage and a bus going to outputStage
    buses.add(Bus.audio(context.server, 2));
    buses.add(Bus.audio(context.server, 2));

    // make the inputStage and outputStage and connect the input and output buses
    inputStage = Synth.new(\passThru, [
      \inL, this.getInL,
      \inR, this.getInR,
      \out, buses[0].index,
      \amp, inputAmp,
    ], context.xg);
    outputStage = Synth.new(\passThru, [
      \inL, buses[1].index,
      \inR, buses[1].index + 1,
      \out, context.out_b.index,
      \amp, outputAmp,
    ], inputStage, \addAfter);

    // Set up commands for board management
    this.addCommand("add_pedal_definition", "s", {|msg| this.addPedalDefinition(msg[1]);});
    this.addCommand("add_pedal", "s", {|msg| this.addPedal(msg[1]);});
    this.addCommand("insert_pedal_at_index", "is", {|msg| this.insertPedalAtIndex(msg[1], msg[2]);});
    this.addCommand("remove_pedal_at_index", "ii", {|msg| this.removePedalAtIndex(msg[1], msg[2] == 1);});
    this.addCommand("swap_pedal_at_index", "is", {|msg| this.swapPedalAtIndex(msg[1], msg[2]);});
    this.addCommand("set_num_input_channels", "i", {|msg| this.setNumInputChannels(msg[1]);});
    this.addCommand("set_input_amp", "f", {|msg| this.setInputAmp(msg[1]);});
    this.addCommand("set_output_amp", "f", {|msg| this.setOutputAmp(msg[1]);});

    this.buildNoPedalState;

    // TODO: before outs, put a basic Limiter.ar(mixdown, 1.0) ?

    // Use this line to test CPU load:
    // fork { loop { [context.server.peakCPU, context.server.avgCPU].postln; 3.wait } };
  }

  buildNoPedalState {
    // Have a no-op "pedal" in the middle to connect buses[1] to buses[2]
    passThru = Synth.new(\passThru, [
      \inL, buses[0].index,
      \inR, buses[0].index + 1,
      \out, buses[1].index,
    ], inputStage, \addAfter);
  }

  addPedalDefinition {|pedalId|
    try {
      optionalPedalLookup[pedalId].addDef(context);
      pedalSetupState[pedalId] = 1;
    } {
      pedalSetupState[pedalId] = 0;
    }
  }

  addPedal {|pedalId|
    this.insertPedalAtIndex(boardIds.size, pedalId);
  }

  insertPedalAtIndex {|index, pedalId|
    var inL, inR, out, target, addAction = \addAfter, indexToRemove = -1;

    // Don't allow inserting beyond the end of the board (other than adding just onto the end)
    if (index > boardIds.size, {
      index = boardIds.size;
    });

    // If the pedal is already elsewhere in the chain, remove it first.
    // TODO: this could be made somewhat more "correct" by saving synths on a board array, rather than in the id lookup.
    // This would mean a rethinking of how the addCommand works (likely: loop over board until match is found)
    if (pedalDetails[pedalId][\synth].notNil, {
      boardIds.do({|item, i| if (item == pedalId, { indexToRemove = i; }); });
    });
    if (indexToRemove != -1, {
      // No work to do if the pedal is already in the right place
      if (indexToRemove == index, { ^this; });
      this.removePedalAtIndex(indexToRemove);
      // This now results in the pedal not being put at exactly `index`,
      // but for now it's necessary given how we don't allow duplicate pedals
      if (index > indexToRemove, { index = index - 1 });
    });

    // Always have our inputs be the original input bus, as set up on line 88
    inL = buses[0].index;
    inR = buses[0].index + 1;
    // Unlike the existing code, we never have to make a new bus or `buses.insert` anything
    // And finally, our output is the bus at index 1
    out = buses[1].index;
    
    // We add ourselves after the prior pedal (or after the inputStage if there's no pedals yet)
    if (index == 0, {
      target = inputStage;
    }, {
      target = pedalDetails[boardIds[index - 1]][\synth];
    });
    if (pedalDetails[pedalId][\synth].notNil, {
      pedalDetails[pedalId][\synth].moveAfter(target);
      pedalDetails[pedalId][\synth].set(\inL, inL, \inR, inR, \out, out);
    }, {
      pedalDetails[pedalId][\synth] = Synth.new(
        pedalId,
        pedalDetails[pedalId][\arguments].merge((inL: inL, inR: inR, out: out)).getPairs,
        target,
        addAction
      );
    });
    if (index == boardIds.size, {
        // If we're inserting at the end, we set the outputStage to have its inputs as our outputs
      outputStage.set(\inL, out, \inR, out + 1);
    }, {
      // Otherwise we set the pedal after us's inputs as our outputs
      pedalDetails[boardIds[index]][\synth].set(\inL, out, \inR, out + 1);
    });
    if (boardIds.size == 0, {
      // If there used to be no pedals, we have to free up the passThru synth
      passThru.free;
    });
    boardIds.insert(index, pedalId);
  }

  removePedalAtIndex {|index, shouldNotFree = false|
    if (boardIds.size == 1, {
      this.buildNoPedalState;
    }, {

    });
    if (shouldNotFree, {}, {
      pedalDetails[boardIds[index]][\synth].free;
      pedalDetails[boardIds[index]][\synth] = nil;
    });
    boardIds.removeAt(index);
  }

  swapPedalAtIndex {|index, newPedalId|
    if (index < boardIds.size, {
      this.removePedalAtIndex(index);
    });
    this.insertPedalAtIndex(index, newPedalId);
  }

  setNumInputChannels {|numChannelsArg|
    numInputChannels = numChannelsArg;
    inputStage.set(\inL, this.getInL, \inR, this.getInR);
  }

  getInL {
    ^context.in_b[0].index;
  }

  getInR {
    if (numInputChannels == 1, {
      ^context.in_b[0].index;
    }, {
      ^context.in_b[1].index;
    });
  }

  setInputAmp {|amp|
    inputAmp = amp;
    inputStage.set(\amp, inputAmp);
  }

  setOutputAmp {|amp|
    outputAmp = amp;
    outputStage.set(\amp, outputAmp);
  }

  free {
    buses.do({|bus| bus.free; });
    allPedalIds.do({|pedalId| pedalDetails[pedalId][\synth].free;});
    outputStage.free;
    passThru.free;
    inputStage.free;
  }
}