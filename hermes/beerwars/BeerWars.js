var helper_methods = {
  setupContract:  function(contract, nameList, address, username, pass) {
    process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0'
    const toBase64 = function(data) {
      const buff = new Buffer(data);
      return buff.toString('base64');
    }

    const basicAuthEncode = function(username, pass) {
      const header = username + ':' + pass;
      return 'Basic ' + toBase64(header);
    }

    try{
      fs = require('fs');
      Web3 = require('web3');
      HttpHeaderProvider = require('httpheaderprovider');
      var endpoint = address;
      const basicAuth = basicAuthEncode(username, pass);
      const header = {'authorization': basicAuth};
      const provider = new HttpHeaderProvider(address, header);
      web3 = new Web3();
      web3.setProvider(provider);
      console.log('Endpoint is ' + endpoint);
      console.log('Loading contract');
      abisrc = fs.readFileSync(contract + '.abi').toString()
      abidef = JSON.parse(abisrc);
      contract_c = web3.eth.contract(abidef);
      bytecode = fs.readFileSync(contract + '.bin').toString()
      console.log('Deploying contract');
      deployed_c = contract_c.new(nameList, {data: bytecode, from: '0x262c0d7ab5ffd4ede2199f6ea793f819e1abb019', gas: 4700000});

      return new Promise(resolve => {
        when_defined(deployed_c, 'address').then(deployed_c => {
          let address = deployed_c.address;
          console.log('Contract has been deployed');
          resolve(address);
        });
      });
    
    }
    catch(err) {
      console.log('Error deploying contract');
      throw err;
    }
  },
  transferBeer: function(contractInstance, fromUserID, toUserID) {
      return contractInstance.transferBeer(fromUserID, toUserID, {from: '0x262c0d7ab5ffd4ede2199f6ea793f819e1abb019'}).toString();
  },
  buyBeer: function(contractInstance, userID) {
      return contractInstance.buyBeer(userID, {from: '0x262c0d7ab5ffd4ede2199f6ea793f819e1abb019'}).toString();
  },
  drinkBeer: function(contractInstance, userID) {
      return contractInstance.drinkBeer(userID, {from: '0x262c0d7ab5ffd4ede2199f6ea793f819e1abb019'}).toString();
  },
  getNumberOfBeers: function(contractInstance, userID) {
    return contractInstance.getNumberOfBeers(userID, {from: '0x262c0d7ab5ffd4ede2199f6ea793f819e1abb019'}).toString();
  },
  addName: function(contractInstance, newUserID) {
      return contractInstance.addName(newUserID, {from: '0x262c0d7ab5ffd4ede2199f6ea793f819e1abb019'}).toString();
  },
  removeName: function(contractInstance, userID) {
      return contractInstance.removeName(userID, {from: '0x262c0d7ab5ffd4ede2199f6ea793f819e1abb019'}).toString();
  }
}

async function when_defined(variable, property){
    return new Promise(resolve => {
      if (variable.property) {
        resolve(variable);
      } 
      else {
        Object.defineProperty(variable, property, {
          configurable: true,
          enumerable: true,
          writeable: true,
          get: function() {
            return this.property;
          },
          set: function(val) {
            this.property = val;
            resolve(variable);
          }
        });
      }
    }).then(function(){
      return variable;
    });
  }

module.exports = helper_methods;
